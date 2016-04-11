package org.elasticsearch.kafka.indexer.jobs;

import kafka.common.ErrorMapping;
import kafka.javaapi.FetchResponse;
import kafka.javaapi.message.ByteBufferMessageSet;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.kafka.indexer.FailedEventsLogger;
import org.elasticsearch.kafka.indexer.exception.IndexerESException;
import org.elasticsearch.kafka.indexer.exception.KafkaClientNotRecoverableException;
import org.elasticsearch.kafka.indexer.exception.KafkaClientRecoverableException;
import org.elasticsearch.kafka.indexer.service.ConsumerConfigService;
import org.elasticsearch.kafka.indexer.service.IMessageHandler;
import org.elasticsearch.kafka.indexer.service.KafkaClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

public class IndexerJob implements Callable<IndexerJobStatus> {

	private static final Logger logger = LoggerFactory.getLogger(IndexerJob.class);
	private ConsumerConfigService configService;
	private IMessageHandler messageHandlerService ;
	public KafkaClientService kafkaClient;
	private long offsetForThisRound;
	private long nextOffsetToProcess;
	private boolean isStartingFirstTime;
	private final int currentPartition;
	private final String currentTopic;
    private IndexerJobStatus indexerJobStatus;
    private volatile boolean shutdownRequested = false;


	public IndexerJob(ConsumerConfigService configService, IMessageHandler messageHandlerService, 
			KafkaClientService kafkaClient, int partition) 
			throws Exception {
		this.configService = configService;
		this.currentPartition = partition;
		this.currentTopic = configService.getTopic();
		this.messageHandlerService = messageHandlerService ;
		indexerJobStatus = new IndexerJobStatus(-1L, IndexerJobStatusEnum.Created, partition);
		isStartingFirstTime = true;
		this.kafkaClient = kafkaClient;
		indexerJobStatus.setJobStatus(IndexerJobStatusEnum.Initialized);
		logger.info("Created IndexerJob for topic={}, partition={};  messageHandlerService={}; kafkaClient={}", 
			currentTopic, partition, messageHandlerService, kafkaClient);
	}

	// a hook to be used by the Manager app to request a graceful shutdown of the job
	public void requestShutdown() {
		shutdownRequested = true;
	}

	public IndexerJobStatus call() {
		indexerJobStatus.setJobStatus(IndexerJobStatusEnum.Started);
        while(!shutdownRequested){
        	try{
        		// check if there was a request to stop this thread - stop processing if so
                if (Thread.currentThread().isInterrupted()){
                    // preserve interruption state of the thread
                    Thread.currentThread().interrupt();
                    throw new InterruptedException(
                    	"Cought interrupted event in IndexerJob for partition=" + currentPartition + " - stopping");
                }
        		logger.debug("******* Starting a new batch of events from Kafka for partition {} ...", currentPartition);
        		
        		processMessagesFromKafka();
        		indexerJobStatus.setJobStatus(IndexerJobStatusEnum.InProgress);	
        		// sleep for configured time
        		// TODO improve sleep pattern
        		Thread.sleep(configService.getConsumerSleepBetweenFetchsMs() * 1000);
        		logger.debug("Completed a round of indexing into ES for partition {}",currentPartition);
        	} catch (IndexerESException|KafkaClientNotRecoverableException e) {
        		indexerJobStatus.setJobStatus(IndexerJobStatusEnum.Failed);
        		stopClients();
        		break;
        	} catch (InterruptedException e) {
        		indexerJobStatus.setJobStatus(IndexerJobStatusEnum.Stopped);
        		stopClients();
        		break;
        	} catch (Exception e){
				if (!reInitKafkaSucceed(e)){
					break;
				}

			}
        }
		logger.warn("******* Indexing job was stopped, indexerJobStatus={} - exiting", indexerJobStatus);
		return indexerJobStatus;
	}

	private boolean reInitKafkaSucceed(Exception e) {
		// we will treat all other Exceptions as recoverable for now
		logger.error("Exception when starting a new round of kafka Indexer job for partition {} - will try to re-init Kafka " ,
                currentPartition, e);
		// try to re-init Kafka connection first - in case the leader for this partition
		// has changed due to a Kafka node restart and/or leader re-election
		// TODO decide if we want to re-try forever or fail here
		// TODO introduce another JobStatus to indicate that the job is in the REINIT state - if this state can take awhile
		try {
            kafkaClient.reInitKafka();
			return true;
        } catch (Exception e2) {
            // we still failed - do not keep going anymore - stop and fix the issue manually,
            // then restart the consumer again; It is better to monitor the job externally
            // via Zabbix or the likes - rather then keep failing [potentially] forever
            logger.error("Exception when starting a new round of kafka Indexer job, partition {}, exiting: "
                    + e2.getMessage(), currentPartition);
            indexerJobStatus.setJobStatus(IndexerJobStatusEnum.Failed);
            stopClients();
			return false;
        }
	}


	public void processMessagesFromKafka() throws Exception {
		long jobStartTime = 0l;
		if (configService.isPerfReportingEnabled()) {
			jobStartTime = System.currentTimeMillis();
		}
		determineOffsetForThisRound(jobStartTime);
		ByteBufferMessageSet byteBufferMsgSet = getMessageAndOffsets(jobStartTime);

		if (byteBufferMsgSet != null) {
			logger.debug("Starting to prepare for post to ElasticSearch for partition {}",currentPartition);
			//Need to save nextOffsetToProcess in temporary field,
			//and save it after successful execution of indexIntoESWithRetries method
			long proposedNextOffsetToProcess = addMessageToElasticBatchRequest(jobStartTime, byteBufferMsgSet);
			if (configService.isDryRun()) {
				logger.info("**** This is a dry run, NOT committing the offset in Kafka nor posting to ES for partition {}****",currentPartition);
				return;
			}
			boolean isPostSuccessful = postBatchToElasticSearch(proposedNextOffsetToProcess);
			if (isPostSuccessful){
				commitOffSet(jobStartTime);
			}
		}
	}

	private void commitOffSet(long jobStartTime) throws KafkaClientRecoverableException {
		if (configService.isPerfReportingEnabled()) {
            long timeAfterEsPost = System.currentTimeMillis();
            logger.debug("Approx time to post of ElasticSearch: {} ms for partition {}",
                    (timeAfterEsPost - jobStartTime),currentPartition);
        }
		logger.info("Commiting offset={} for partition={}", nextOffsetToProcess, currentPartition);
		// do not handle exceptions here - throw them out to the call() method which will decide if re-init
		// of Kafka should be attempted or not
		try {
            kafkaClient.saveOffsetInKafka(nextOffsetToProcess, ErrorMapping.NoError());
        } catch (Exception e) {
            logger.error("Failed to commit nextOffsetToProcess={} after processing and posting to ES for partition={}: ",
                    nextOffsetToProcess, currentPartition, e);
            throw new KafkaClientRecoverableException("Failed to commit nextOffsetToProcess=" + nextOffsetToProcess +
                    " after processing and posting to ES; partition=" + currentPartition + "; error: " + e.getMessage(), e);
        }

		if (configService.isPerfReportingEnabled()) {
            long timeAtEndOfJob = System.currentTimeMillis();
            logger.info("*** This round of IndexerJob took about {} ms for partition {} ",
                    (timeAtEndOfJob - jobStartTime),currentPartition);
        }
		logger.info("*** Finished current round of IndexerJob, processed messages with offsets [{}-{}] for partition {} ****",
                offsetForThisRound, nextOffsetToProcess, currentPartition);
	}

	private boolean postBatchToElasticSearch(long proposedNextOffsetToProcess) throws Exception {
		// TODO we are loosing the ability to set Job's status to HANGING in case ES is unreachable and
		// re-connect to ES takes awhile ... See if it is possible to re-introduce it in another way
		try {
            logger.info("About to post messages to ElasticSearch for partition={}, offsets {}-->{} ",
                    currentPartition, offsetForThisRound, proposedNextOffsetToProcess-1);
            messageHandlerService.postToElasticSearch();
        } catch (IndexerESException e) {
            // TODO re-process batch - right now, we fail the whole batch in the call() and shutdown the Job
            logger.error("Error posting messages to Elastic Search for offsets {}-->{} " +
                            " in partition={} - will re-try processing the batch; error: {}",
                    offsetForThisRound, proposedNextOffsetToProcess-1, currentPartition, e.getMessage());
			return false;
        } catch (ElasticsearchException e) {
            // we are assuming that these exceptions are data-specific - continue and commit the offset,
            // but be aware that ALL messages from this batch are NOT indexed into ES
            logger.error("Error posting messages to ElasticSearch for offset {}-->{} in partition {} skipping them: ",
                    offsetForThisRound, proposedNextOffsetToProcess-1, currentPartition, e);
            FailedEventsLogger.logFailedEvent(offsetForThisRound, proposedNextOffsetToProcess-1, currentPartition, e.getDetailedMessage(), null);
        }

		nextOffsetToProcess = proposedNextOffsetToProcess;
		return true;
	}

	private long addMessageToElasticBatchRequest(long jobStartTime, ByteBufferMessageSet byteBufferMsgSet) {
		long proposedNextOffsetToProcess = messageHandlerService.prepareForPostToElasticSearch(byteBufferMsgSet.iterator());

		if (configService.isPerfReportingEnabled()) {
            long timeAtPrepareES = System.currentTimeMillis();
            logger.debug("Completed preparing for post to ElasticSearch. Approx time taken: {}ms for partition {}",
                    (timeAtPrepareES - jobStartTime),currentPartition );
        }
		return proposedNextOffsetToProcess;
	}

	private ByteBufferMessageSet getMessageAndOffsets(long jobStartTime) throws Exception {
		// do not handle exceptions here - they will be taken care of in the computeOffset()
		// and exception will be thrown to the call() method which will decide if re-init
		// of Kafka should be attempted or not
		FetchResponse fetchResponse = kafkaClient.getMessagesFromKafka(offsetForThisRound);
		if (fetchResponse.hasError()) {
			// check what kind of error this is - for most errors, we will try to re-init Kafka;
			// in the case of OffsetOutOfRange - we may have to roll back to the earliest offset
			short errorCode = fetchResponse.errorCode(currentTopic, currentPartition);
			Long newNextOffsetToProcess = kafkaClient.handleErrorFromFetchMessages(errorCode, offsetForThisRound);
			if (newNextOffsetToProcess != null) {
				// this is the case when we have to re-set the nextOffsetToProcess
				nextOffsetToProcess = newNextOffsetToProcess;
			}
			// return - will try to re-process the batch again
			return null;
		}

		ByteBufferMessageSet byteBufferMsgSet = fetchResponse.messageSet(currentTopic, currentPartition);
		if (configService.isPerfReportingEnabled()) {
			long timeAfterKafkaFetch = System.currentTimeMillis();
			logger.debug("Completed MsgSet fetch from Kafka. Approx time taken is {} ms for partition {}",
				(timeAfterKafkaFetch - jobStartTime) ,currentPartition);
		}
		if (byteBufferMsgSet.validBytes() <= 0) {
			logger.debug("No events were read from Kafka - finishing this round of reads from Kafka for partition {}",currentPartition);
			// TODO re-review this logic
			// check a corner case when consumer did not read any events form Kafka from the last current offset -
			// but the latestOffset reported by Kafka is higher than what consumer is trying to read from;
			long latestOffset = kafkaClient.getLastestOffset();
			if (latestOffset != offsetForThisRound) {
				logger.warn("latestOffset={} for partition={} is not the same as the offsetForThisRound for this run: {}" +
					" - returning; will try reading messages form this offset again ",
					latestOffset, currentPartition, offsetForThisRound);
				// TODO decide if we really need to do anything here - for now:
				// do not do anything, just return, and let the consumer try to read again from the same offset;
				// do not handle exceptions here - throw them out to the call() method which will decide if re-init
				// of Kafka should be attempted or not
				/*
				try {
					kafkaClient.saveOffsetInKafka(latestOffset, ErrorMapping.NoError());
				} catch (Exception e) {
					logger.error("Failed to commit nextOffsetToProcess={} after processing and posting to ES for partition={}: ",
							nextOffsetToProcess, currentPartition, e);
					throw new KafkaClientRecoverableException("Failed to commit nextOffsetToProcess=" + nextOffsetToProcess +
						" after processing and posting to ES; partition=" + currentPartition + "; error: " + e.getMessage(), e);
				}
				/*  */
			}
			return null;
		}
		return byteBufferMsgSet;
	}

	private void determineOffsetForThisRound(long jobStartTime) throws Exception {

		if (!isStartingFirstTime) {
			// do not read offset from Kafka after each run - we just stored it there
			// If this is the only thread that is processing data from this partition -
			// we can rely on the in-memory nextOffsetToProcess variable
			offsetForThisRound = nextOffsetToProcess;
		} else {
			indexerJobStatus.setJobStatus(IndexerJobStatusEnum.InProgress);
			// if this is the first time we run the Consumer - get it from Kafka
			// do not handle exceptions here - they will be taken care of in the computeOffset()
			// and exception will be thrown to the call() method which will decide if re-init
			// of Kafka should be attempted or not
			offsetForThisRound = kafkaClient.computeInitialOffset();
			// mark this as not first time startup anymore - since we already saved correct offset
			// to Kafka, and to avoid going through the logic of figuring out the initial offset
			// every round if it so happens that there were no events from Kafka for a long time
			isStartingFirstTime = false;
			nextOffsetToProcess = offsetForThisRound;
		}
		//TODO  see if we are doing this too early - before we actually commit the offset
		indexerJobStatus.setLastCommittedOffset(offsetForThisRound);
		return ;
	}

	public void stopClients() {
		logger.info("About to stop Kafka client for topic {}, partition {}", currentTopic, currentPartition);
		if (kafkaClient != null)
			kafkaClient.close();
		logger.info("Stopped Kafka client for topic {}, partition {}", currentTopic, currentPartition);
	}
	
	public IndexerJobStatus getIndexerJobStatus() {
		return indexerJobStatus;
	}

}

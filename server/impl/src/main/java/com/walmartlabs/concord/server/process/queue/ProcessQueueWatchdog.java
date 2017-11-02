package com.walmartlabs.concord.server.process.queue;

import com.walmartlabs.concord.db.AbstractDao;
import com.walmartlabs.concord.project.InternalConstants;
import com.walmartlabs.concord.server.Utils;
import com.walmartlabs.concord.server.agent.AgentManager;
import com.walmartlabs.concord.server.api.process.ProcessKind;
import com.walmartlabs.concord.server.api.process.ProcessStatus;
import com.walmartlabs.concord.server.jooq.tables.VProcessQueue;
import com.walmartlabs.concord.server.process.Payload;
import com.walmartlabs.concord.server.process.PayloadManager;
import com.walmartlabs.concord.server.process.logs.LogManager;
import com.walmartlabs.concord.server.process.pipelines.ForkPipeline;
import com.walmartlabs.concord.server.process.pipelines.processors.Chain;
import com.walmartlabs.concord.server.process.state.ProcessMetadataManager;
import org.eclipse.sisu.EagerSingleton;
import org.jooq.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.walmartlabs.concord.server.jooq.Tables.V_PROCESS_QUEUE;
import static com.walmartlabs.concord.server.jooq.tables.ProcessState.PROCESS_STATE;
import static org.jooq.impl.DSL.*;

@Named
@EagerSingleton
public class ProcessQueueWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ProcessQueueWatchdog.class);

    private static final PollEntry POLL_ENTRIES[] = {
            new PollEntry(ProcessStatus.FAILED,
                    ProcessMetadataManager.ON_FAILURE_MARKER_PATH,
                    InternalConstants.Flows.ON_FAILURE_FLOW,
                    ProcessKind.FAILURE_HANDLER, 3),

            new PollEntry(ProcessStatus.CANCELLED,
                    ProcessMetadataManager.ON_CANCEL_MARKER_PATH,
                    InternalConstants.Flows.ON_CANCEL_FLOW,
                    ProcessKind.CANCEL_HANDLER, 3)
    };

    private static final long HANDLERS_POLL_DELAY = 2000;
    private static final long HANDLERS_ERROR_DELAY = 10000;

    private static final long STALLED_POLL_DELAY = 10000;
    private static final long STALLED_ERROR_DELAY = 10000;

    private static final ProcessKind[] HANDLED_PROCESS_KINDS = {
            ProcessKind.DEFAULT
    };

    private static final ProcessKind[] SPECIAL_HANDLERS = {
            ProcessKind.FAILURE_HANDLER,
            ProcessKind.CANCEL_HANDLER
    };

    private static final ProcessStatus[] ACTIVE_PROCESS_STATUSES = {
            ProcessStatus.SUSPENDED,
            ProcessStatus.ENQUEUED,
            ProcessStatus.RUNNING,
            ProcessStatus.PREPARING,
            ProcessStatus.RESUMING
    };

    private static final ProcessStatus[] POTENTIAL_STALLED_STATUSES = {
            ProcessStatus.RUNNING,
            ProcessStatus.STARTING
    };

    private final ProcessQueueDao queueDao;
    private final LogManager logManager;
    private final AgentManager agentManager;
    private final WatchdogDao watchdogDao;
    private final PayloadManager payloadManager;
    private final Chain forkPipeline;

    @Inject
    public ProcessQueueWatchdog(ProcessQueueDao queueDao, LogManager logManager,
                                AgentManager agentManager, WatchdogDao watchdogDao, PayloadManager payloadManager,
                                ForkPipeline forkPipeline) {

        this.queueDao = queueDao;
        this.logManager = logManager;
        this.agentManager = agentManager;
        this.watchdogDao = watchdogDao;
        this.payloadManager = payloadManager;
        this.forkPipeline = forkPipeline;

        init();
    }

    private void init() {
        new Thread(new Worker(HANDLERS_POLL_DELAY, HANDLERS_ERROR_DELAY, new ProcessHandlersWorker()),
                "process-handlers-worker").start();

        new Thread(new Worker(STALLED_POLL_DELAY, STALLED_ERROR_DELAY, new ProcessStalledWorker()),
                "process-stalled-worker").start();

        log.info("init -> watchdog started");
    }

    private static final class Worker implements Runnable {

        private final long interval;
        private final long errorInterval;
        private final Runnable delegate;

        private Worker(long interval, long errorInterval, Runnable delegate) {
            this.interval = interval;
            this.errorInterval = errorInterval;
            this.delegate = delegate;
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    delegate.run();
                    sleep(interval);
                } catch (Exception e) {
                    log.error("run -> error: {}", e.getMessage(), e);
                    sleep(errorInterval);
                }
            }
        }

        private void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final class ProcessHandlersWorker implements Runnable {

        @Override
        public void run() {
            watchdogDao.transaction(tx -> {
                Field<Timestamp> maxAge = currentTimestamp().minus(field("interval '7 days'"));

                for (PollEntry e : POLL_ENTRIES) {
                    List<ProcessEntry> parents = watchdogDao.poll(tx, e, maxAge, 1);

                    for (ProcessEntry parent : parents) {
                        UUID childId = UUID.randomUUID();

                        Map<String, Object> req = new HashMap<>();
                        req.put(InternalConstants.Request.ENTRY_POINT_KEY, e.flow);
                        req.put(InternalConstants.Request.TAGS_KEY, null); // clear tags

                        Payload payload = payloadManager.createFork(childId, parent.instanceId, e.handlerKind,
                                parent.initiator, parent.projectId, req, null);

                        forkPipeline.process(payload);

                        log.info("processHandlers -> created a new child process '{}' (parent '{}', entryPoint: '{}')",
                                childId, parent.instanceId, e.flow);
                    }
                }
            });
        }
    }

    private final class ProcessStalledWorker implements Runnable {
        @Override
        public void run() {
            watchdogDao.transaction(tx -> {
                Field<Timestamp> cutOff = currentTimestamp().minus(field("interval '1 minute'"));

                List<UUID> ids = watchdogDao.pollStalled(tx, cutOff, 1);
                for (UUID id : ids) {
                    queueDao.updateAgentId(tx, id, null, ProcessStatus.FAILED);
                    logManager.warn(id, "Process stalled, no heartbeat for more than a minute");

                    agentManager.killProcess(id);
                    log.info("processStalled -> marked as failed: {}", id);
                }
            });
        }
    }

    private static final class PollEntry {

        private final ProcessStatus status;
        private final String marker;
        private final String flow;
        private final ProcessKind handlerKind;
        private final int maxTries;

        private PollEntry(ProcessStatus status, String marker, String flow, ProcessKind handlerKind, int maxTries) {
            this.status = status;
            this.marker = marker;
            this.flow = flow;
            this.handlerKind = handlerKind;
            this.maxTries = maxTries;
        }
    }

    @Named
    private static final class WatchdogDao extends AbstractDao {

        @Inject
        public WatchdogDao(Configuration cfg) {
            super(cfg);
        }

        public void transaction(Tx t) {
            tx(t);
        }

        public List<ProcessEntry> poll(DSLContext tx, PollEntry entry, Field<Timestamp> maxAge, int maxEntries) {
            VProcessQueue q = V_PROCESS_QUEUE.as("q");

            return tx.select(q.INSTANCE_ID, q.PROJECT_ID, q.INITIATOR)
                    .from(q)
                    .where(q.PROCESS_KIND.in(Utils.toString(HANDLED_PROCESS_KINDS))
                            .and(q.CURRENT_STATUS.eq(entry.status.toString()))
                            .and(q.CREATED_AT.greaterOrEqual(maxAge))
                            .and(existsMarker(q.INSTANCE_ID, entry.marker))
                            .and(notExistsSuccess(tx, q.INSTANCE_ID, entry.handlerKind))
                            .and(count(tx, q.INSTANCE_ID, entry.handlerKind).lessThan(entry.maxTries))
                            .and(noRunningHandlers(tx, q.INSTANCE_ID)))
                    .limit(maxEntries)
                    .forUpdate()
                    .skipLocked()
                    .fetch(WatchdogDao::toEntry);
        }

        public List<UUID> pollStalled(DSLContext tx, Field<Timestamp> cutOff, int maxEntries) {
            VProcessQueue q = V_PROCESS_QUEUE.as("q");

            return tx.select(q.INSTANCE_ID)
                    .from(q)
                    .where(q.CURRENT_STATUS.in(Utils.toString(POTENTIAL_STALLED_STATUSES))
                            .and(q.LAST_UPDATED_AT.lessThan(cutOff)))
                    .orderBy(q.CREATED_AT)
                    .limit(maxEntries)
                    .forUpdate()
                    .skipLocked()
                    .fetch(q.INSTANCE_ID);
        }

        private Field<Number> count(DSLContext tx, Field<UUID> parentInstanceId, ProcessKind kind) {
            return tx.selectCount()
                    .from(V_PROCESS_QUEUE)
                    .where(V_PROCESS_QUEUE.PARENT_INSTANCE_ID.eq(parentInstanceId)
                            .and(V_PROCESS_QUEUE.PROCESS_KIND.eq(kind.toString())))
                    .asField();
        }

        private Condition notExistsSuccess(DSLContext tx, Field<UUID> parentInstanceId, ProcessKind kind) {
            return notExists(tx.selectFrom(V_PROCESS_QUEUE)
                    .where(V_PROCESS_QUEUE.PARENT_INSTANCE_ID.eq(parentInstanceId)
                            .and(V_PROCESS_QUEUE.PROCESS_KIND.eq(kind.toString()))
                            .and(V_PROCESS_QUEUE.CURRENT_STATUS.eq(ProcessStatus.FINISHED.toString()))));
        }

        private Condition noRunningHandlers(DSLContext tx, Field<UUID> parentInstanceId) {
            return notExists(tx.selectFrom(V_PROCESS_QUEUE)
                    .where(V_PROCESS_QUEUE.PARENT_INSTANCE_ID.eq(parentInstanceId)
                            .and(V_PROCESS_QUEUE.CURRENT_STATUS.in(Utils.toString(ACTIVE_PROCESS_STATUSES)))
                            .and(V_PROCESS_QUEUE.PROCESS_KIND.in(Utils.toString(SPECIAL_HANDLERS)))));
        }

        private Condition existsMarker(Field<UUID> instanceId, String marker) {
            return exists(selectFrom(PROCESS_STATE).where(PROCESS_STATE.INSTANCE_ID.eq(instanceId)
                    .and(PROCESS_STATE.ITEM_PATH.eq(marker))));
        }

        private static ProcessEntry toEntry(Record3<UUID, UUID, String> r) {
            return new ProcessEntry(r.get(V_PROCESS_QUEUE.INSTANCE_ID),
                    r.get(V_PROCESS_QUEUE.PROJECT_ID),
                    r.get(V_PROCESS_QUEUE.INITIATOR));
        }
    }

    private static final class ProcessEntry implements Serializable {

        private final UUID instanceId;
        private final UUID projectId;
        private final String initiator;

        private ProcessEntry(UUID instanceId, UUID projectId, String initiator) {
            this.instanceId = instanceId;
            this.projectId = projectId;
            this.initiator = initiator;
        }
    }
}

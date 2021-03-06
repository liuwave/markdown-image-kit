package info.dong4j.idea.plugin.sdk.qcloud.cos.transfer;

import info.dong4j.idea.plugin.sdk.qcloud.cos.COS;
import info.dong4j.idea.plugin.sdk.qcloud.cos.event.ProgressEventType;
import info.dong4j.idea.plugin.sdk.qcloud.cos.event.ProgressListenerChain;
import info.dong4j.idea.plugin.sdk.qcloud.cos.exception.CosClientException;
import info.dong4j.idea.plugin.sdk.qcloud.cos.internal.CopyImpl;
import info.dong4j.idea.plugin.sdk.qcloud.cos.model.CopyObjectRequest;
import info.dong4j.idea.plugin.sdk.qcloud.cos.model.CopyResult;
import info.dong4j.idea.plugin.sdk.qcloud.cos.model.PartETag;
import info.dong4j.idea.plugin.sdk.qcloud.cos.transfer.Transfer.TransferState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static info.dong4j.idea.plugin.sdk.qcloud.cos.event.SDKProgressPublisher.publishProgress;

public class CopyMonitor implements Callable<CopyResult>, TransferMonitor {
    /**
     * Reference to the COS client object that is used to initiate the copy
     * or copy part request.
     */
    private final COS cos;
    /** Thread pool used during multi-part copy is performed. */
    private final ExecutorService threadPool;
    /** A reference to the original copy request received. */
    private final CopyObjectRequest origReq;
    /** Reference to the CopyCallable that is used for initiating copy requests. */
    private final CopyCallable multipartCopyCallable;
    private final CopyImpl transfer;
    private final ProgressListenerChain listener;

    private final List<Future<PartETag>> futures = new ArrayList<Future<PartETag>>();

    /*
     * State for clients wishing to poll for completion
     */
    private boolean isCopyDone = false;
    private AtomicReference<Future<CopyResult>> futureReference = new AtomicReference<Future<CopyResult>>(null);

    public Future<CopyResult> getFuture() {
        return futureReference.get();
    }

    public synchronized boolean isDone() {
        return isCopyDone;
    }

    private synchronized void markAllDone() {
        isCopyDone = true;
    }

    /**
     * Constructs a new watcher for copy operation, and then immediately submits
     * it to the thread pool.
     *
     * @param manager
     *            The {@link TransferManager} that owns this copy request.
     * @param threadPool
     *            The {@link ExecutorService} to which we should submit new
     *            tasks.
     * @param multipartCopyCallable
     *            The callable responsible for processing the copy
     *            asynchronously
     * @param copyObjectRequest
     *            The original CopyObject request
     */
    public static CopyMonitor create(
            TransferManager manager,
            CopyImpl transfer,
            ExecutorService threadPool,
            CopyCallable multipartCopyCallable,
            CopyObjectRequest copyObjectRequest,
            ProgressListenerChain progressListenerChain) {

        CopyMonitor copyMonitor = new CopyMonitor(manager, transfer,
                threadPool, multipartCopyCallable, copyObjectRequest,
                progressListenerChain);
        Future<CopyResult> thisFuture = threadPool.submit(copyMonitor);
        // Use an atomic compareAndSet to prevent a possible race between the
        // setting of the CopyMonitor's futureReference, and setting the
        // CompleteMultipartCopy's futureReference within the call() method.
        // We only want to set the futureReference to CopyMonitor's futureReference if the
        // current value is null, otherwise the futureReference that's set is
        // CompleteMultipartCopy's which is ultimately what we want.
        copyMonitor.futureReference.compareAndSet(null, thisFuture);
        return copyMonitor;
    }

    private CopyMonitor(TransferManager manager, CopyImpl transfer,
                        ExecutorService threadPool, CopyCallable multipartCopyCallable,
                        CopyObjectRequest copyObjectRequest,
                        ProgressListenerChain progressListenerChain) {

        this.cos = manager.getCOSClient();
        this.multipartCopyCallable = multipartCopyCallable;
        this.origReq = copyObjectRequest;
        this.listener = progressListenerChain;
        this.transfer = transfer;
        this.threadPool = threadPool;
    }

    @Override
    public CopyResult call() throws Exception {
        try {
            CopyResult result = multipartCopyCallable.call();

            if (result == null) {
                futures.addAll(multipartCopyCallable.getFutures());
                futureReference.set(threadPool.submit(new CompleteMultipartCopy(multipartCopyCallable.getMultipartUploadId(), cos, origReq,
                        futures, listener, this)));
            } else {
                copyComplete();
            }
            return result;
        } catch (CancellationException e) {
            transfer.setState(TransferState.Canceled);
            publishProgress(listener, ProgressEventType.TRANSFER_CANCELED_EVENT);
            throw new CosClientException("Upload canceled");
        } catch (Exception e) {
            transfer.setState(TransferState.Failed);
            publishProgress(listener, ProgressEventType.TRANSFER_FAILED_EVENT);
            throw e;
        }
    }

    void copyComplete() {
        markAllDone();
        transfer.setState(TransferState.Completed);
        if (multipartCopyCallable.isMultipartCopy()) {
            publishProgress(listener, ProgressEventType.TRANSFER_COMPLETED_EVENT);
        }
    }
}

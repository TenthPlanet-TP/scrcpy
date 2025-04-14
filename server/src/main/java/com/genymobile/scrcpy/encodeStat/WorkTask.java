package com.genymobile.scrcpy.encodeStat;

// import android.util.Ln;
import com.genymobile.scrcpy.util.Ln;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class WorkTask {
    // private static final String TAG = WorkTask.class.getSimpleName();

    // private static final ExecutorService executor =
    // Executors.newSingleThreadExecutor();
    // private ExecutorService executor = Executors.newSingleThreadExecutor();
    private String mName;

    // 创建一个自定义的线程工厂，设置线程优先级为最高
    ThreadFactory threadFactory = new ThreadFactory() {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setPriority(Thread.MAX_PRIORITY); // 设置最高优先级
            t.setName("high-priority-thread-" + threadNumber.getAndIncrement());
            return t;
        }
    };

    // 使用自定义线程工厂创建单线程执行器
    // ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);

    ExecutorService executor = null;

    public WorkTask(String name) {
        mName = name;
        
        executor = Executors.newSingleThreadExecutor();
        Ln.d(name + " create: " + executor);
    }

    public WorkTask(String name, boolean isHighPriority) {
        mName = name;

        if (isHighPriority) {
            executor = Executors.newSingleThreadExecutor(threadFactory);
        } else {
            executor = Executors.newSingleThreadExecutor();
        }
        Ln.d(name + " create: " + executor);
    }

    public void executeTask(Runnable task) {
        try {
            if (executor == null) {
                Ln.e("Executor is null, cannot execute task.");
                return;
            }
            // 检查线程池是否终止
            if (executor.isTerminated()) {
                Ln.e("Executor is terminated, cannot execute task.");
                return;
            }
            // 尝试提交任务
            executor.execute(task);
            return;
        } catch (RejectedExecutionException e) {
            // 捕获异常
            Ln.e("RejectedExecutionException: " + e);
            e.printStackTrace();
            // throw new RuntimeException(e);
            return;
        } catch (Exception e) {
            // 捕获其他可能的异常
            Ln.e("Exception when executing task: " + e.getMessage());
            return;
        }
    }

    public void release() {
        Ln.d(mName + " destory: " + executor);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow(); // 强制停止所有正在执行的任务
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}

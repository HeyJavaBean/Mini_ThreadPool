package com.imlehr.test;

import java.util.Scanner;
import java.util.concurrent.*;

/**
 * @author Lehr
 * @create: 2020-09-25
 */
public class PoolTest {

    public static void main(String[] args) throws Exception{

        Integer maxThreads = 4;
        Integer coreThreads = 1;
        //关于超时设置为0？
        Long keepAliveTime = 1L;
        TimeUnit unit = TimeUnit.SECONDS;
        //这个是无界的，很坑！
        //BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue();
        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(2);
        ThreadFactory threadFactory = Executors.defaultThreadFactory();
        RejectedExecutionHandler handler = new ThreadPoolExecutor.AbortPolicy();

        //ExecutorService executorService = new ThreadPoolExecutor(coreThreads, maxThreads,  keepAliveTime, unit, workQueue,threadFactory,handler);


        com.imlehr.pool.ThreadPoolExecutor executorService = new com.imlehr.pool.ThreadPoolExecutor(coreThreads, maxThreads, keepAliveTime, unit, workQueue, threadFactory);
        Runnable task = ()->
        {
            for(int i=0;i<5;i++)
            {
                System.err.println(Thread.currentThread().getName()+":Hey!-"+i);
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };

        for(int i=0;i<8;i++)
        {
            executorService.execute(task);
        }

        TimeUnit.SECONDS.sleep(20);

        executorService.shutdown();


    }



}

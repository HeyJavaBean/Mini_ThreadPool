package com.imlehr.pool;


import javafx.concurrent.Worker;

import java.util.HashSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Lehr
 * @create: 2020-09-25
 */
public class ThreadPoolExecutor implements Executor{

    /**
     * 表示线程池运行状态
     * 32位
     * 高位3个表示状态
     * 剩下29位表示工作线程数量
     */
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    private static final int COUNT_BITS = Integer.SIZE - 3;
    private static final int CAPACITY = (1 << COUNT_BITS) - 1;

    /**
     * 111是RUNNING
     * 001是STOP
     * 000是SHUTDOWN
     * 看下嘛，反正越底则Run
     * runState is stored in the high-order bits
     * 我这里设计的简单，反正就只有两个状态了
     */


    /**
     * new出线程池后就是RUNNING
     * 接受新任务
     */
    private static final int RUNNING = -1 << COUNT_BITS;
    /**
     * 不接受新任务，但是是会把剩下的任务处理了的
     */
    private static final int SHUTDOWN = 0 << COUNT_BITS;

    /**
     * 通过低29位获得线程数目
     *
     * @param c
     * @return
     */
    private static int workerCountOf(int c) {
        return c & CAPACITY;
    }

    /**
     * 设定信息生成ctl的值
     *
     * @return
     */
    private static int ctlOf(int status, int workerNum) {
        return status | workerNum;
    }

    /**
     * 我的简化设计里反正不是running就是shutdown
     *
     * @param c
     * @return
     */
    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    /**
     * 用来保证workers安全的
     * 但是看源码的有些操作我有点没get到是什么意思
     */
    private ReentrantLock mainLock = new ReentrantLock();

    /**
     * 准备一个HashSet放Workers
     * 添加方法是在addWorker里
     * 移除操作是在runWorker里面生命结束的时候调用
     * 用MainLock保证线程安全
     */
    private HashSet<Worker> workers = new HashSet();

    /**
     * 只是个属性记录，在真正的实现里由于中途是可以修改的还要考虑并发安全所以是volatile
     * 而我这里简单实现就只是一次设定完了就不用care这些了
     */
    private int corePoolSize;
    private int maximumPoolSize;

    /**
     * 超时判定的等待时间
     * 在线程数超出核心线程组之后尝试getTask的时候会采用poll方法超时获取
     * 从而决定一系列超时判定机制
     */
    private Long keepAliveTime;

    /**
     * 放task的阻塞队列，用来让getTask的Worker阻塞住
     */
    private BlockingQueue<Runnable> workQueue;

    /**
     * 一种方便获取线程的方法，我这里就懒得多写了
     */
    private ThreadFactory threadFactory;

    /**
     * 初始化这些设置而已
     * @param corePoolSize
     * @param maximumPoolSize
     * @param keepAliveTime
     * @param unit
     * @param workQueue
     * @param threadFactory
     */
    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
        if (corePoolSize >= 0 && maximumPoolSize > 0 && maximumPoolSize >= corePoolSize && keepAliveTime >= 0L) {
            if (workQueue != null && threadFactory != null) {
                this.corePoolSize = corePoolSize;
                this.maximumPoolSize = maximumPoolSize;
                this.workQueue = workQueue;
                this.keepAliveTime = unit.toNanos(keepAliveTime);
                this.threadFactory = threadFactory;
            } else {
                throw new NullPointerException();
            }
        } else {
            throw new IllegalArgumentException();
        }
    }


    /**
     * 给入一个任务然后开始试着执行
     * 逻辑如下
     * 1.核心组没有满，则开个新的Worker线程执行
     * 2.核心组满了，尝试往队列里放
     * 3.往队列里放了都失败了（当然是有界队列哈），则开始最大线程组开新线程处理
     * 4.如果线程数量已经到最大了，那么采用拒绝策略拒绝他
     * @param command
     */
    @Override
    public void execute(Runnable command) {

        //获取线程池信息
        int c = ctl.get();
        //当核心组没满的时候往里加，如果并发失败了就进入后续处理
        if (workerCountOf(c) < corePoolSize) {
            if (addWorker(command, true)) {
                return;
            }
            c = ctl.get();
        }
        //需要排队的情况，这里只是简单设置了RUNNING SHUTDOWN
        if (isRunning(c) && workQueue.offer(command)) {
            //如果正在运行and能够成功排队
            //源码在这里还有个double-check的操作，据说是为了防止放入后线程池状态突变，所以可能remove出来
            //这里设计的简单就不考虑了
            System.err.println("I'm Waiting");
        }
        //最后是排队满了尝试扩容如果失败直接拒绝的情况
        else if (!addWorker(command, false)) {
            System.err.println("滚nm的！");
        }
    }


    /**
     * 增加一个线程，这里的情况考虑的比较简单粗暴
     * 每个Worker线程刚刚创建的时候都会有个firstTask
     * isCore决定是否是属于核心线程组的
     * 这个过程的流程图
     * https://tech.meituan.com/2020/04/02/java-pooling-pratice-in-meituan.html
     *
     * @param firstTask
     * @param isCore
     * @return
     */
    private boolean addWorker(Runnable firstTask, boolean isCore) {

        for (;;) {
            int c = ctl.get();
            //如果线程池shutdown了就不接受addWorker了
            if (!isRunning(c)) {
                System.err.println("该下班了，老子不干了！");
                return false;
            }

            //数量检查
            if (workerCountOf(c) >= (isCore ? corePoolSize : maximumPoolSize)) {
                return false;
            }
            //CAS登记，如果失败了则重新尝试 注意这里是C
            if (ctl.compareAndSet(c, c + 1)) {
                break;
            }
        }

        //登记完成了，然后准备新线程并封装到worker里去
        Worker w = new Worker(firstTask);
        //源码还要顺带检查一下thread是不是空的
        mainLock.lock();
        try{
            //安全地把这个线程放入到set里去
            //这里就简单写了，直接加入了
            workers.add(w);
            //开始工作，走的是worker的run方法后再进入runWorker方法
            w.thread.start();
        }finally {
            mainLock.unlock();
        }
        return true;
    }

    /**
     * 简单粗糙的设计而已，没什么太多状态
     */
    public void shutdown() {
        System.err.println("xdm，准备下班了");
        int c;
        //CAS修改状态
        do{
            c = ctl.get();
        }while(ctl.compareAndSet(c, ctlOf(SHUTDOWN, workerCountOf(c))));

        tryTerminate();
    }


    /**
     * 这里大概就可以理解为，等到所有线程都结束了队列也为空了吧
     */
    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            if (!isRunning(c)  && workQueue.isEmpty() && workerCountOf(c)==0)
            {
                break;
            }
            //System.err.println("疯狂自旋等待所有任务完结");
        }
        System.err.println("finally done!");
    }


    /**
     * Worker类，包装了线程操作，解决了循环利用的问题
     */
    private final class Worker implements Runnable {

        /**
         * 这个worker拿到的任务
         */
        Runnable firstTask;
        /**
         * 这个worker所包装的线程
         */
        Thread thread;

        public Worker(Runnable firstTask) {
            this.firstTask = firstTask;
            //从工厂里生成一个线程，就等于是包装了自己
            this.thread = threadFactory.newThread(this);
        }


        @Override
        public void run() {
            System.err.println(thread.getName()+":我要开干了！");
            runWorker(this);
            System.err.println(thread.getName()+":啊!我被鲨啦！");
        }

        final void runWorker(Worker w) {

            //获取执行线程 and 任务
            Runnable task = w.firstTask;
            //任务滞空，代表已经在做了
            w.firstTask = null;
            //不断执行任务，and去getTask阻塞拿取
            while (task != null || (task = getTask()) != null) {
                task.run();
                task = null;
            }

            //结束后退出
            processWorkerExit(w);
        }

        private void processWorkerExit(Worker w) {
            //这里就没有completedAbruptly来考虑线程意外退出的情况
            //默认在操作之前已经登记数量--了
            mainLock.lock();
            try {
                workers.remove(w);
            } finally {
                mainLock.unlock();
            }
            //源码比这个而复杂些没get到点子上
        }

        private Runnable getTask() {

            //用来表示线程是否遇到取任务超时的情况（空闲了）
            boolean timedOut = false;

            //大循环是用来给销毁条件做CAS的
            for (;;) {
                int c = ctl.get();
                // 如果线程池已经进入了SHUTDOWN状态，且队伍里的东西都处理干净了，则返回null，此worker被销毁
                if (!isRunning(c) && (workQueue.isEmpty())) {
                    //CAS操作做计数销毁
                    while (!ctl.compareAndSet(c, c -1)){
                        System.err.println(thread.getName()+":活干完了该下班了");
                        c = ctl.get();
                    }
                    return null;
                }

                //获取当前线程个数
                int wc = workerCountOf(c);


                //是否走超时处理流程
                //判定目前是否会触发超时等待机制（源码里默认是不支持超时的，由allowCoreThreadTimeOut默认为false决定）
                //如果总线程数目超过了核心线程组的，那么正好这个线程就赶上了等待被处理
                //源码里的allowCoreThreadTimeOut如果设置为开似乎连核心组都要杀
                boolean timed =  wc > corePoolSize ;

                //首先我们看上次这个线程成功取到数据没有，如果没有，则说明他是超时了的，那么如果同意超时处理，那么他就会被办掉
                boolean condition1 = wc > maximumPoolSize || (timed && timedOut);

                //确定至少要保留一个线程？？  然后另外的条件是等待队列是空的？？
                boolean condition2 = wc > 1 || workQueue.isEmpty();

                //是否触发大清洗操作
                if (condition1 && condition2) {
                    if (ctl.compareAndSet(wc, wc -1)) {
                        System.err.println(thread.getName()+":啊，我被大清洗了！");
                        return null;
                    }
                    continue;
                }

                //去拉取新的任务
                try {
                    //poll的区别是，可以等待一段时间后返回null
                    //take则是直接触发阻塞

                    //如果有超时处理机制，则我们可以试图获取后返回null
                    //如果一直是走核心组的话就不会出现这个问题
                    Runnable r = timed ?
                            workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                            workQueue.take();
                    if (r != null) {
                        return r;
                    }
                    //代表获取超时了，那么下次循环处理的时候他就会有可能被超时干掉
                    timedOut = true;
                } catch (InterruptedException retry) {
                    timedOut = false;
                }
            }
        }





    }
}



package com.bonree.brfs.rebalance.recover;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.bonree.brfs.common.rebalance.Constants;
import com.bonree.brfs.common.service.Service;
import com.bonree.brfs.common.service.ServiceManager;
import com.bonree.brfs.common.utils.FileUtils;
import com.bonree.brfs.common.zookeeper.curator.CuratorClient;
import com.bonree.brfs.common.zookeeper.curator.cache.AbstractNodeCacheListener;
import com.bonree.brfs.common.zookeeper.curator.cache.CuratorCacheFactory;
import com.bonree.brfs.common.zookeeper.curator.cache.CuratorNodeCache;
import com.bonree.brfs.configuration.ServerConfig;
import com.bonree.brfs.rebalance.DataRecover;
import com.bonree.brfs.rebalance.record.BalanceRecord;
import com.bonree.brfs.rebalance.record.SimpleRecordWriter;
import com.bonree.brfs.rebalance.task.BalanceTaskSummary;
import com.bonree.brfs.rebalance.task.TaskDetail;
import com.bonree.brfs.rebalance.task.TaskStatus;
import com.bonree.brfs.rebalance.transfer.SimpleFileClient;
import com.bonree.brfs.server.identification.ServerIDManager;

/*******************************************************************************
 * 版权信息：博睿宏远科技发展有限公司
 * Copyright: Copyright (c) 2007博睿宏远科技发展有限公司,Inc.All Rights Reserved.
 * 
 * @date 2018年3月23日 下午2:16:13
 * @Author: <a href=mailto:weizheng@bonree.com>魏征</a>
 * @Description: 恢复虚拟ServerID的
 ******************************************************************************/
public class VirtualRecover implements DataRecover {

    private final static Logger LOG = LoggerFactory.getLogger(VirtualRecover.class);

    private static final String NAME_SEPARATOR = "_";

    private final String dataDir;

    private final String storageName;

    private ServerIDManager idManager;

    private final String taskNode;

    private final String selfNode;

    private BalanceTaskSummary balanceSummary;

    private CuratorNodeCache nodeCache;
    
    private SimpleFileClient fileClient;

    private final CuratorClient client;

    private final long delayTime;

    private final ServiceManager serviceManager;

    private boolean overFlag = false;

    private TaskDetail detail;

    private int currentCount = 0;

    private final BlockingQueue<FileRecoverMeta> fileRecoverQueue = new ArrayBlockingQueue<>(2000);

    private AtomicReference<TaskStatus> status = null;

    private class RecoverListener extends AbstractNodeCacheListener {

        public RecoverListener(String listenName) {
            super(listenName);
        }

        @Override
        public void nodeChanged() throws Exception {
            LOG.info("node change!!!");
            if (client.checkExists(taskNode)) {
                byte[] data = client.getData(taskNode);
                BalanceTaskSummary bts = JSON.parseObject(data, BalanceTaskSummary.class);
                TaskStatus stats = bts.getTaskStatus();
                // 更新缓存
                status.set(stats);
                LOG.info("stats:" + stats);
            }
        }

    }

    public VirtualRecover(CuratorClient client, BalanceTaskSummary balanceSummary, String taskNode, String dataDir, String storageName, ServerIDManager idManager, ServiceManager serviceManager) {
        this.balanceSummary = balanceSummary;
        this.taskNode = taskNode;
        this.client = client;
        this.idManager = idManager;
        this.serviceManager = serviceManager;
        this.dataDir = dataDir;
        this.storageName = storageName;
        this.fileClient = new SimpleFileClient();
        // 恢复需要对节点进行监听
        nodeCache = CuratorCacheFactory.getNodeCache();
        nodeCache.addListener(taskNode, new RecoverListener("recover"));
        this.selfNode = taskNode + Constants.SEPARATOR + this.idManager.getFirstServerID();
        this.delayTime = balanceSummary.getDelayTime();
        status = new AtomicReference<TaskStatus>(balanceSummary.getTaskStatus());
    }

    @Override
    public void recover() {
        try {
            for (int i = 0; i < delayTime; i++) {
                if (status.get().equals(TaskStatus.CANCEL)) {
                    return;
                }
                // 已注册任务，则直接退出
                if (client.checkExists(selfNode)) {
                    break;
                }
                LOG.info("remain time:" + (delayTime - i) + "s, start task!!!");
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            LOG.error("task back time count interrupt!!", e);
        }

        LOG.info("begin virtual recover");
        
        detail = new TaskDetail(idManager.getFirstServerID(), ExecutionStatus.INIT, 0, 0, 0);
        // 注册节点
        LOG.info("create:" + selfNode + "-------------" + detail);
        // 无注册的话，则注册，否则不用注册
        registerNode(selfNode, detail);

        detail.setStatus(ExecutionStatus.RECOVER);
        LOG.info("update:" + selfNode + "-------------" + detail);
        updateDetail(selfNode, detail);

        int timeFileCounts = 0;
        String snDataDir = dataDir + FileUtils.FILE_SEPARATOR + storageName;

        if (!FileUtils.isExist(snDataDir)) {
            finishTask();
            return;
        }

        List<String> replicasNames = FileUtils.listFileNames(snDataDir);
        for (String replicasName : replicasNames) {
            String replicasPath = snDataDir + FileUtils.FILE_SEPARATOR + replicasName;
            timeFileCounts += FileUtils.listFileNames(replicasPath).size();
        }

        Thread cosumerThread = new Thread(consumerQueue());
        cosumerThread.start();
        
        detail.setTotalDirectories(timeFileCounts);
        updateDetail(selfNode, detail);

        String remoteSecondId = balanceSummary.getInputServers().get(0);
        String remoteFirstID = idManager.getOtherFirstID(remoteSecondId, balanceSummary.getStorageIndex());
        String virtualID = balanceSummary.getServerId();
        LOG.info("balance virtual serverId:" + virtualID);
        QUIT: for (String replicasName : replicasNames) { // 处理的副本编号
            String replicaPath = snDataDir + FileUtils.FILE_SEPARATOR + replicasName;
            List<String> timeFileNames = FileUtils.listFileNames(replicaPath);
            for (String timeFileName : timeFileNames) {// 时间文件
                SimpleRecordWriter simpleWriter = null;
                String timeFilePath = replicaPath + FileUtils.FILE_SEPARATOR + timeFileName;
                String recordPath = timeFilePath + FileUtils.FILE_SEPARATOR + "xxoo.rd";
                try {
                    simpleWriter = new SimpleRecordWriter(recordPath);
                    List<String> fileNames = FileUtils.listFileNames(timeFilePath, ".rd");
                    for (String fileName : fileNames) {

                        if (status.get().equals(TaskStatus.CANCEL)) {
                            break QUIT;
                        }

                        int replicaPot = 0;
                        String[] metaArr = fileName.split(NAME_SEPARATOR);
                        List<String> fileServerIds = new ArrayList<>();
                        for (int j = 1; j < metaArr.length; j++) {
                            fileServerIds.add(metaArr[j]);
                        }
                        if (fileServerIds.contains(virtualID)) {
                            // 此处位置需要加1，副本数从1开始
                            replicaPot = fileServerIds.indexOf(virtualID) + 1;
                            FileRecoverMeta fileMeta = new FileRecoverMeta(fileName, storageName, timeFileName, Integer.parseInt(replicasName), replicaPot, remoteFirstID, simpleWriter);
                            try {
                                fileRecoverQueue.put(fileMeta);
                            } catch (InterruptedException e) {
                                LOG.error("put file: " + fileMeta, e);
                            }
                        }
                    }
                } catch (FileNotFoundException e1) {
                    e1.printStackTrace();
                } finally {
                    if (simpleWriter != null) {
                        try {
                            simpleWriter.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        // 所有的文件已经处理完毕，等待队列为空
        overFlag = true;
        try {
            cosumerThread.join();
        } catch (InterruptedException e) {
            LOG.error("cosumerThread error!", e);
        }

        finishTask();
    }

    public void finishTask() {
        // 没有中断
        if (status.get().equals(TaskStatus.RUNNING)) {
            detail.setStatus(ExecutionStatus.FINISH);
            LOG.info("update:" + selfNode + "-------------" + detail);
            updateDetail(selfNode, detail);
            LOG.info("virtual server id:" + balanceSummary.getServerId() + " transference over!!!");
            LOG.info("虚拟恢复正常完成！！！！！");
        }

        try {
            nodeCache.cancelListener(taskNode);
        } catch (IOException e) {
            LOG.error("cancel listener failed!!", e);
        }
    }

    public boolean isExistFile(String remoteServerId, String fileName) {
        return false;
    }

    private Runnable consumerQueue() {
        return new Runnable() {

            @Override
            public void run() {
                try {
                    FileRecoverMeta fileRecover = null;
                    while (fileRecover != null || !overFlag) {
                        if (status.get().equals(TaskStatus.CANCEL)) {
                            break;
                        }
                        fileRecover = fileRecoverQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (fileRecover != null) {
                            String logicPath = storageName + FileUtils.FILE_SEPARATOR + fileRecover.getReplica() + FileUtils.FILE_SEPARATOR + fileRecover.getTime();
                            String remoteDir = storageName + FileUtils.FILE_SEPARATOR + fileRecover.getPot() + FileUtils.FILE_SEPARATOR + fileRecover.getTime();
                            String localFilePath = dataDir + FileUtils.FILE_SEPARATOR + logicPath + FileUtils.FILE_SEPARATOR + fileRecover.getFileName();
                            boolean success = false;
                            LOG.info("transfer :" + fileRecover);
                            String firstID = fileRecover.getFirstServerID();
                            Service service = serviceManager.getServiceById(ServerConfig.DEFAULT_DISK_NODE_SERVICE_GROUP, firstID);
                            while (true) {

                                // if (!diskClient.isExistFile(service.getHost(), service.getPort(), logicPath)) {
                                success = sucureCopyTo(service, localFilePath,remoteDir,fileRecover.getFileName());
                                // }
                                if (success) {
                                    break;
                                }
                            }

                            currentCount += 1;
                            detail.setCurentCount(currentCount);
                            detail.setProcess(detail.getCurentCount() / (double) detail.getTotalDirectories());
                            updateDetail(selfNode, detail);
                            if (success) {
                                BalanceRecord record = new BalanceRecord(fileRecover.getFileName(), idManager.getSecondServerID(balanceSummary.getStorageIndex()), fileRecover.getFirstServerID());
                                // fileRecover.getSimpleWriter().writeRecord(record.toString());
                            }
                            LOG.info("update:" + selfNode + "-------------" + detail);
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };
    }

    public boolean sucureCopyTo(Service service, String localPath,String remoteDir, String fileName) {
        boolean success = true;
        try {
//            diskClient.copyTo(service.getHost(), service.getPort(), logicPath, logicPath);
            fileClient.sendFile(service.getHost(), service.getPort()+20, localPath, remoteDir, fileName);
        } catch (Exception e) {
            success = false;
            e.printStackTrace();
        }
        return success;
    }

    /** 概述：更新任务信息
     * @param node
     * @param status
     * @user <a href=mailto:weizheng@bonree.com>魏征</a>
     */
    public void updateDetail(String node, TaskDetail detail) {
        if (client.checkExists(node)) {
            try {
                client.setData(node, JSON.toJSONString(detail).getBytes());
            } catch (Exception e) {
                LOG.error("change Task status error!", e);
            }
        }
    }

    /** 概述：注册节点
     * @param node
     * @user <a href=mailto:weizheng@bonree.com>魏征</a>
     */
    public void registerNode(String node, TaskDetail detail) {
        if (!client.checkExists(node)) {
            client.createPersistent(node, false, JSON.toJSONString(detail).getBytes());
        }
    }

}

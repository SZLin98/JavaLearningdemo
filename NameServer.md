简介：

NameServer是RocketMQ的路由中心，Broker消息存储服务器在启动的时候要向所有的NameServer注册自己的ip。

消息的生产者在发送消息前，需要从NameServer中获取所有消费者的地址，然后根据配置的负载均衡策略选择一个Broker发送消息。

NameServer与每一台Broker保持长连接并间隔10s检测Broker是否存活，如果发现Broker宕机了则将其从自己的路由表中移除，但是路由变化不会自动地通知生产者（这个传闻是为了降低NameServer的复杂性）

核心架构：

1、Broker每隔30s向NameServer集群的每一台机器发送自己的心跳包，包含自身的topic路由等信息

2、消息客户端（Broker？//todo）每隔30s向nameServer更新对应topic路由信息

3、nameService收到broker发送的心跳的时候，会记录一下每个broker的心跳包的时间戳

4、NameServer每隔10s会扫描brokerLiveTable（记录每个broker心跳包的时间戳的map）如果在距离现在已经120s了还没有收到心跳包，则认为Broker失效了，则更新topic路由信息将失效的broker信息移除。

核心代码：

1、NameServer启动类：

org.apache.rocketmq.namesrv.NamesrvStartup

```java
public static void main0(String[] args) {
        try{
            //加载解析配置文件
            parseCommandlineAndConfigFile(args);
            //启动Controller
            createAndStartNamesrvController();
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }
```

1、首先加载配置文件，将配置文件中的属性赋值给配置的config类，如namesrvConfig（NameServer业务参数，如存储配置路径等）、nettyServerConfig（NameServer网络参数）、nettyClientConfig、controllerConfig

2、将NameServerController实例初始化，该类为NameServer核心控制器

```java
//将上面几个配置的类作为初始化参数初始化controller
controller = new NamesrvController(namesrvConfig, nettyServerConfig, nettyClientConfig
//记录夏配置文件所有的属性信息的这个类
controller.getConfiguration().registerConfig(properties););
```

controller.initialize()

```java
public boolean initialize() {
        //加载KV配置
        loadConfig();
        //设置netty远程服务的客户端
        initiateNetworkComponents();
        //初始化线程池defaultExecutor、clientRequestExecutor
        initiateThreadExecutors();
        //注册网络请求处理器
        registerProcessor();
        //开启定时任务：每十秒定时移除过期的broker和定期打印当前nameServer的kv配置
        startScheduleService();
        //ssl安全认证相关，不是核心功能
        initiateSslContext();
        initiateRpcHooks();
        return true;
    }
```

```java
private void startScheduleService() {
        //定时扫描过期的broker
        this.scanExecutorService.scheduleAtFixedRate(NamesrvController.this.routeInfoManager::scanNotActiveBroker,
            5, this.namesrvConfig.getScanNotActiveBrokerInterval(), TimeUnit.MILLISECONDS);
        //定时打印kv配置
        this.scheduledExecutorService.scheduleAtFixedRate(NamesrvController.this.kvConfigManager::printAllPeriodically,
            1, 10, TimeUnit.MINUTES);
        //打印出来当前网络请求的队列长度的信息
        this.scheduledExecutorService.scheduleAtFixedRate(() -> {
            try {
                NamesrvController.this.printWaterMark();
            } catch (Throwable e) {
                LOGGER.error("printWaterMark error.", e);
            }
        }, 10, 1, TimeUnit.SECONDS);
    }
```

扫描心跳包做路由删除

```java
public void scanNotActiveBroker() {
        try {
            log.info("start scanNotActiveBroker");
            for (Entry<BrokerAddrInfo, BrokerLiveInfo> next : this.brokerLiveTable.entrySet()) {
                long last = next.getValue().getLastUpdateTimestamp();
                long timeoutMillis = next.getValue().getHeartbeatTimeoutMillis();
                if ((last + timeoutMillis) < System.currentTimeMillis()) {
                    RemotingUtil.closeChannel(next.getValue().getChannel());
                    log.warn("The broker channel expired, {} {}ms", next.getKey(), timeoutMillis);
                    this.onChannelDestroy(next.getKey());
                }
            }
        } catch (Exception e) {
            log.error("scanNotActiveBroker exception", e);
        }
    }
```

然后再注册一个JVM钩子函数以关闭controller，然后启动controller。在JVM进程关闭之前，先将线程池关闭，及时释放资源

```java
Runtime.getRuntime().addShutdownHook(new ShutdownHookThread(log, (Callable<Void>) () -> {
            //关闭各类线程池
            controller.shutdown();
            return null;
        }));
        //开启netty远程交互端remotingServer、remotingClient，
        //然后再开启routeInfoManager这个管理broker路由地址的类（这个开启不知道有扫描用）
        controller.start();
```

2、NameServer路由注册、故障删除

这个部分是NameServer的核心功能，即为消息生产者和消费者提供关于topic的路由信息，其中存储了broker的各路由信息和管理broker节点。

1、路由存储的类是上面的routeInfoManager，

```java
//topic路由信息
private final Map<String/* topic */, Map<String, QueueData>> topicQueueTable;
//broker基础信息，包含了brokerName、所属的集群名称、主备broker的地址
private final Map<String/* brokerName */, BrokerData> brokerAddrTable;
//broker集群的名称，存储集群中所有broker名称是
private final Map<String/* clusterName */, Set<String/* brokerName */>> clusterAddrTable;
//broker心跳信息，记录每个broker最后发送心跳的时间戳
private final Map<BrokerAddrInfo/* brokerAddr */, BrokerLiveInfo> brokerLiveTable;
//据说是类模式的消息过滤，不知道干啥用的，看着不是核心功能
private final Map<BrokerAddrInfo/* brokerAddr */, List<String>/* Filter Server */> filterServerTable;
//topic的各broker的name的各个broker的id
private final Map<String/* topic */, Map<String/*brokerName*/, TopicQueueMappingInfo>> topicQueueMappingInfoTable;
```

一个topic中有多个消息队列，一个broker默认为每一个topic创建4个读队列和4个写队列。多个broker组成一个集群，brokerName由相同的多台broker组成主从结构，brokerId=0代表主节点，brokerId>0代表从节点。brokerLiveInfo中的lastUpdateTimestamp存储上次收到的该broker心跳包时候的时间戳。

```java
class QueueData implements Comparable<QueueData> {
    private String brokerName;
    private int readQueueNums;
    private int writeQueueNums;
    private int perm;
    private int topicSysFlag;
}
class BrokerData {
    //broker所属的集群名称
    private String cluster;
    //brokerName
    private String brokerName
    //该brokerName下面的主从结构的各broker地址，brokerId=0的是主节点
    private HashMap<Long, String> brokerAddrs;;
}
class BrokerLiveInfo{
    //最后一次心跳包的时间
    private long lastUpdateTimestamp;
    private long heartbeatTimeoutMillis;
    private DataVersion dataVersion;
    private Channel channel;
    private String haServerAddr;
}
```

broker的主从结构

```java
cluster:c1                    cluster:c1
brokerName:broker-a           brokerName:broker-a
brokerId:0                    brokerId:1

主                    →          从

cluster:c1                    cluster:c1
brokerName:broker-b           brokerName:broker-b
brokerId:0                    brokerId:1
```

routeInfoManager的各主要的类的运行时内存结构

topicQueueTable:

```java
topicQueueTable:{
    "topic":[
        {
            "brokerName":"broker-a",
            "readQueueNums":4,
            "writeQueueNums":4,
            "perm":6,        //读写权限
            "topicSynFlag":0    //topic同步标记
        },
        {
            "brokerName":"broker-b",
            "readQueueNums":4,
            "writeQueueNums":4,
            "perm":6,        //读写权限
            "topicSynFlag":0    //topic同步标记
        }
    ]            
}    
```

brokerAddressTable:

```java
brokerAddrTable:{
    "broker-a":{
        "cluster":"c1",
        "brokerName":"broker-a",
        "brokerAddrs":{
            0:"192.168.56.1:10000",
            1:"192.168.56.2:10000"
        }
    },
    "broker-b":{
        "cluster":"c1",
        "brokerName":"broker-b",
        "brokerAddrs":{
            0:"192.168.56.3:10000",
            1:"192.168.56.4:10000"
        }
    },
}
```

brokerLiveTable:

```java
brokerLiveTable:{
    "192.168.56.1:10000":{
        "lastUpdateTimeStamp":151359413364,
        "dataVersion":versionObj,
        "channel":channelObj,
        "haServerAddr":"192.168.56.2:10000"
    },
    "192.168.56.2:10000":{
        "lastUpdateTimeStamp":151359413364,
        "dataVersion":versionObj,
        "channel":channelObj,
        "haServerAddr":""
    },
    "192.168.56.3:10000":{
        "lastUpdateTimeStamp":151359413364,
        "dataVersion":versionObj,
        "channel":channelObj,
        "haServerAddr":"192.168.56.4:10000"
    },
    "192.168.56.4:10000":{
        "lastUpdateTimeStamp":151359413364,
        "dataVersion":versionObj,
        "channel":channelObj,
        "haServerAddr":""
    }
}
```

clusterAddrTable

```java
clusterAddrTable:{
    "c1":[{"broker-a","broker-b"}]
}
```

2.3 路由注册

Broker是启动的时候向集群的所有NameServer发送心跳包，并且每隔30s向集群中所有NameServer发送心跳包，NameServer收到了broker的心跳后会更新brokerLiveTableTable缓存中BrokerLiveInfo的lastUpdateTimeStamp的心跳包时间戳，然后用定时任务每隔10s扫描一次brokerLiveTable的心跳包距离现在的时间，如果超过了120s没有收到心跳包的话，则NameServer将把该broker的路由移除并关闭socket的连接

1、Broker发送心跳包

在BrokerController的start函数中

```java
scheduledFutures.add(this.scheduledExecutorService.scheduleAtFixedRate(new AbstractBrokerRunnable(this.getBrokerIdentity()) {
            @Override
            public void run2() {
                try {
                    if (System.currentTimeMillis() < shouldStartTime) {
                        BrokerController.LOG.info("Register to namesrv after {}", shouldStartTime);
                        return;
                    }
                    if (isIsolated) {
                        BrokerController.LOG.info("Skip register for broker is isolated");
                        return;
                    }
                    BrokerController.this.registerBrokerAll(true, false, brokerConfig.isForceRegister());
                } catch (Throwable e) {
                    BrokerController.LOG.error("registerBrokerAll Exception", e);
                }
            }
        }, 1000 * 10, Math.max(10000, Math.min(brokerConfig.getRegisterNameServerPeriod(), 60000)), TimeUnit.MILLISECONDS));
```

 BrokerOuterAPI的registerBrokerAll

```java
//获取所有nameServer的赋值
List<String> nameServerAddressList = this.remotingClient.getAvailableNameSrvList();
if (nameServerAddressList != null && nameServerAddressList.size() > 0) {
        //将broker自身的信息构造成请求
            final RegisterBrokerRequestHeader requestHeader = new RegisterBrokerRequestHeader();
            //broker地址
            requestHeader.setBrokerAddr(brokerAddr);
            //brokerId=0是主节点，>0是从节点
            requestHeader.setBrokerId(brokerId);
            //broker名称
            requestHeader.setBrokerName(brokerName);
            //所属的集群的名称
            requestHeader.setClusterName(clusterName);
            //主节点的地址，初次请求的时候是空，从节点向nameServer注册后返回结果中拿到
            requestHeader.setHaServerAddr(haServerAddr);
            requestHeader.setEnableActingMaster(enableActingMaster);
            requestHeader.setCompressed(false);
            if (heartbeatTimeoutMillis != null) {
                requestHeader.setHeartbeatTimeoutMillis(heartbeatTimeoutMillis);
            }

            RegisterBrokerBody requestBody = new RegisterBrokerBody();
            requestBody.setTopicConfigSerializeWrapper(TopicConfigAndMappingSerializeWrapper.from(topicConfigWrapper));
            requestBody.setFilterServerList(filterServerList);
            final byte[] body = requestBody.encode(compressed);
            final int bodyCrc32 = UtilAll.crc32(body);
            requestHeader.setBodyCrc32(bodyCrc32);
            final CountDownLatch countDownLatch = new CountDownLatch(nameServerAddressList.size());
            //轮询依次向各nameServer发送心跳包注册
            for (final String namesrvAddr : nameServerAddressList) {
                brokerOuterExecutor.execute(new AbstractBrokerRunnable(brokerIdentity) {
                    @Override
                    public void run2() {
                        try {
                //发送心跳包给nameServer注册自己
                            RegisterBrokerResult result = registerBroker(namesrvAddr, oneway, timeoutMills, requestHeader, body);
                            if (result != null) {
                               //这个列表是注册成功的结果的列表
                                registerBrokerResultList.add(result);
                            }

                            LOGGER.info("Registering current broker to name server completed. TargetHost={}", namesrvAddr);
                        } catch (Exception e) {
                            LOGGER.error("Failed to register current broker to name server. TargetHost={}", namesrvAddr, e);
                        } finally {
                            countDownLatch.countDown();
                        }
                    }
                });
            }

            try {
                if (!countDownLatch.await(timeoutMills, TimeUnit.MILLISECONDS)) {
                    LOGGER.warn("Registration to one or more name servers does NOT complete within deadline. Timeout threshold: {}ms", timeoutMills);
                }
            } catch (InterruptedException ignore) {
            }
        }
```

registerBroker函数

```java
RemotingCommand request = RemotingCommand.createRequestCommand(RequestCode.REGISTER_BROKER, requestHeader);
        request.setBody(body);

        if (oneway) {
            try {
                this.remotingClient.invokeOneway(namesrvAddr, request, timeoutMills);
            } catch (RemotingTooMuchRequestException e) {
                // Ignore
            }
            return null;
        }

        RemotingCommand response = this.remotingClient.invokeSync(namesrvAddr, request, timeoutMills);
        assert response != null;
        switch (response.getCode()) {
            case ResponseCode.SUCCESS: {
                RegisterBrokerResponseHeader responseHeader =
                        (RegisterBrokerResponseHeader) response.decodeCommandCustomHeader(RegisterBrokerResponseHeader.class);
                RegisterBrokerResult result = new RegisterBrokerResult();
                result.setMasterAddr(responseHeader.getMasterAddr());
                result.setHaServerAddr(responseHeader.getHaServerAddr());
                if (response.getBody() != null) {
                    result.setKvTable(KVTable.decode(response.getBody(), KVTable.class));
                }
                return result;
            }
            default:
                break;
        }

        throw new MQBrokerException(response.getCode(), response.getRemark(), requestHeader == null ? null : requestHeader.getBrokerAddr());
```

这里RocketMQ的每一个请求都会定义一个RequestCode，对每个code来区分处理逻辑

2、NameServer处理心跳包

org,apache.rocketmq.namesrv.processor.DefaultRequestProcessor是网络处理器解析请求类型，如果请求类型为RequestCode.REGISTER_BROKER，则请求会最终转发到RouteInfoManager的registerBroker，

代码：routeInfoManager的register：

```java
//加写锁，防止并发修改routeInfoManager的路由表
this.lock.writeLock().lockInterruptibly();

//init or update the cluster info
//找出该cluster集合下的所有brokerName
Set<String> brokerNames = ConcurrentHashMapUtils.computeIfAbsent((ConcurrentHashMap<String, Set<String>>) this.clusterAddrTable, clusterName, k -> new HashSet<>());
//将该brokerName添加到集合中
brokerNames.add(brokerName);

boolean registerFirst = false;
//找该brokerName下的地址信息，这里主要是找是否已经有该brokerName
BrokerData brokerData = this.brokerAddrTable.get(brokerName);
if (null == brokerData) {
//为空的话是初次注册，把brokerName信息加入brokerAddrTable
   registerFirst = true;
   brokerData = new BrokerData(clusterName, brokerName, new HashMap<>());
  this.brokerAddrTable.put(brokerName, brokerData);
}
//enableActingMaster不为空且为true的时候，brokerData才能设置成可以为master
boolean isOldVersionBroker = enableActingMaster == null;
brokerData.setEnableActingMaster(!isOldVersionBroker && enableActingMaster);
brokerData.setZoneName(zoneName);

Map<Long, String> brokerAddrsMap = brokerData.getBrokerAddrs();
//要查出来目前最小的brokerId是什么
boolean isMinBrokerIdChanged = false;
long prevMinBrokerId = 0;
if (!brokerAddrsMap.isEmpty()) {
   prevMinBrokerId = Collections.min(brokerAddrsMap.keySet());
}
//如果当前的brokerId更小，则设置最小brokerId发生了变化
if (brokerId < prevMinBrokerId) {
   isMinBrokerIdChanged = true;
}
//如果原先的broker地址的记录的map中有一样的address并且brokerId不同
//那么把brokerId替换成新的，因为一个brokerAddress只有一个broker不能重复
brokerAddrsMap.entrySet().removeIf(item -> null != brokerAddr && brokerAddr.equals(item.getValue()) && brokerId != item.getKey());

//If Local brokerId stateVersion bigger than the registering one,
//查看该brokerId之前在该brokerName的地址map中是否有注册过地址了
String oldBrokerAddr = brokerAddrsMap.get(brokerId);
if (null != oldBrokerAddr && !oldBrokerAddr.equals(brokerAddr)) {
   BrokerLiveInfo oldBrokerInfo = brokerLiveTable.get(new BrokerAddrInfo(clusterName, oldBrokerAddr));

if (null != oldBrokerInfo) {
   long oldStateVersion = oldBrokerInfo.getDataVersion().getStateVersion();
   long newStateVersion = topicConfigWrapper.getDataVersion().getStateVersion();
   //如果老版本大于新版本，去除掉原心跳表
    if (oldStateVersion > newStateVersion) {
      log.warn("Registered Broker conflicts with the existed one, just ignore.: Cluster:{}, BrokerName:{}, BrokerId:{}, " +
           "Old BrokerAddr:{}, Old Version:{}, New BrokerAddr:{}, New Version:{}.",
           clusterName, brokerName, brokerId, oldBrokerAddr, oldStateVersion, brokerAddr, newStateVersion);
           //Remove the rejected brokerAddr from brokerLiveTable.
           brokerLiveTable.remove(new BrokerAddrInfo(clusterName, brokerAddr));
           return result;
      }
   }
}

if (!brokerAddrsMap.containsKey(brokerId) && topicConfigWrapper.getTopicConfigTable().size() == 1) {
                log.warn("Can't register topicConfigWrapper={} because broker[{}]={} has not registered.",
                    topicConfigWrapper.getTopicConfigTable(), brokerId, brokerAddr);
                return null;
            }
            //将新地址put进去，然后获取到旧地址
            String oldAddr = brokerAddrsMap.put(brokerId, brokerAddr);
            registerFirst = registerFirst || (StringUtils.isEmpty(oldAddr));
            //是否是master或者是当前最小的brokerId
            boolean isMaster = MixAll.MASTER_ID == brokerId;
            boolean isPrimeSlave = !isOldVersionBroker && !isMaster
                && brokerId == Collections.min(brokerAddrsMap.keySet());

            //broker是初次注册或者topic配置信息变化的话，则更新topic路由元数据
            if (null != topicConfigWrapper && (isMaster || isPrimeSlave)) {

                ConcurrentMap<String, TopicConfig> tcTable =
                    topicConfigWrapper.getTopicConfigTable();
                if (tcTable != null) {
                    for (Map.Entry<String, TopicConfig> entry : tcTable.entrySet()) {
                        if (registerFirst || this.isTopicConfigChanged(clusterName, brokerAddr,
                            topicConfigWrapper.getDataVersion(), brokerName,
                            entry.getValue().getTopicName())) {
                            final TopicConfig topicConfig = entry.getValue();
                            if (isPrimeSlave) {
                                // Wipe write perm for prime slave
                                topicConfig.setPerm(topicConfig.getPerm() & (~PermName.PERM_WRITE));
                            }
                            this.createAndUpdateQueueData(brokerName, topicConfig);
                        }
                    }
                }

                if (this.isBrokerTopicConfigChanged(clusterName, brokerAddr, topicConfigWrapper.getDataVersion()) || registerFirst) {
                    TopicConfigAndMappingSerializeWrapper mappingSerializeWrapper = TopicConfigAndMappingSerializeWrapper.from(topicConfigWrapper);
                    Map<String, TopicQueueMappingInfo> topicQueueMappingInfoMap = mappingSerializeWrapper.getTopicQueueMappingInfoMap();
                    //the topicQueueMappingInfoMap should never be null, but can be empty
                    for (Map.Entry<String, TopicQueueMappingInfo> entry : topicQueueMappingInfoMap.entrySet()) {
                        if (!topicQueueMappingInfoTable.containsKey(entry.getKey())) {
                            topicQueueMappingInfoTable.put(entry.getKey(), new HashMap<>());
                        }
                        //Note asset brokerName equal entry.getValue().getBname()
                        //here use the mappingDetail.bname
                        topicQueueMappingInfoTable.get(entry.getKey()).put(entry.getValue().getBname(), entry.getValue());
                    }
                }
            }
            //更新broker的心跳信息
            BrokerAddrInfo brokerAddrInfo = new BrokerAddrInfo(clusterName, brokerAddr);
            BrokerLiveInfo prevBrokerLiveInfo = this.brokerLiveTable.put(brokerAddrInfo,
                new BrokerLiveInfo(
                    System.currentTimeMillis(),
                    timeoutMillis == null ? DEFAULT_BROKER_CHANNEL_EXPIRED_TIME : timeoutMillis,
                    topicConfigWrapper == null ? new DataVersion() : topicConfigWrapper.getDataVersion(),
                    channel,
                    haServerAddr));
            if (null == prevBrokerLiveInfo) {
                log.info("new broker registered, {} HAService: {}", brokerAddrInfo, haServerAddr);
            }

            if (filterServerList != null) {
                if (filterServerList.isEmpty()) {
                    this.filterServerTable.remove(brokerAddrInfo);
                } else {
                    this.filterServerTable.put(brokerAddrInfo, filterServerList);
                }
            }
            //如果这个节点是从节点的话，要把master的地址设置给该broker
            if (MixAll.MASTER_ID != brokerId) {
                String masterAddr = brokerData.getBrokerAddrs().get(MixAll.MASTER_ID);
                if (masterAddr != null) {
                    BrokerAddrInfo masterAddrInfo = new BrokerAddrInfo(clusterName, masterAddr);
                    BrokerLiveInfo masterLiveInfo = this.brokerLiveTable.get(masterAddrInfo);
                    if (masterLiveInfo != null) {
                        result.setHaServerAddr(masterLiveInfo.getHaServerAddr());
                        result.setMasterAddr(masterAddr);
                    }
                }
            }
            //如果最小的brokerId改变了，通知该brokerName下的所有broker
            if (isMinBrokerIdChanged && namesrvConfig.isNotifyMinBrokerIdChanged()) {
                notifyMinBrokerIdChanged(brokerAddrsMap, null,
                    this.brokerLiveTable.get(brokerAddrInfo).getHaServerAddr());
            }
```

2.3.3 路由删除    扫描brokerLiveTable，这个定时任务在上面的NameServer启动的时候有一个函数专门启动定时函数的

2.3.4 路由发现

RocketMQ路由发现是非实时的，当topic路由出现了变化后，NameServer并不会主动推送给客户端，而是考客户端主动拉取最新路由的时候发现的。

跟上面一个说的，根据RequestCode来决定调哪个函数，这里Code为GET_ROUTEINFO_BY_TOPIC就是拉去最新路由的信息

拉取路由信息的函数是DefaultRequestProcessor的getRouteInfoByTopic

数据结构TopicRouteData

```java
{
    private String orderTopicConf;
    private List queueDatas;
    private List brokerDatas;
    private HashMap filterServerTable;
}
```

第一步。先从路由表的topicQueueTable、brokerAddrTable、filterServerTable分别填充topicRouteData的List<QueueData>、List<BrokerData>和filterServer地址表

第二部：如果找到主题对应的路由信息并且该主题如果是顺序消息的话，则从NameServerKVConfig中获取关于顺序消息相关的配置来填充要返回的路由信息。

如果找不到路由信息，则返回TOPIC_NOT_EXISTS，表示没有该topic的路由

RockerMQ消息支持三种发送方式：同步（sync）、异步（async）和单向（one way）

1）同步：发送者执行了发送消息的api后，同步等待，直到消息服务器返回发送结果

2）异步：发送者向mq执行发送消息的api后，指定消息发送成功后的回调函数，调用api后立即返回，发送者的线程不阻塞直到运行结束。消息发送成功或者失败的回调函数在一个新线程中执行

3）单向：消息发送者执行了发送消息的api后，直接返回不等待消息服务器的结果，也不注册回调函数。就是只管发送，不在乎发送结果

1.1 topic路由机制

发送消息前，需要查询topic的路由信息。首次发送时会根据topic的名称向nameServer集群查询topic的路由信息，然后将其存在本地，并且每隔30s依次遍历缓存中的topic，向nameServer查询最新的路由信息。如果成功查询到，则将新路由信息更新至本地缓存，实现topic路由信息的动态感知（不是nameServer主动推送）

mq有自动创建topic的机制，如果消息发送者向一个不存在的主题发送消息，向nameServer查询该主题的路由信息会先返回空，如果开启了主动创建主题机制的话，会使用一个默认的主题名再次从nameServer查询路由信息，然后消息发送者会使用默认主题的路由信息进行负载均衡，但不会直接使用默认路由信息为新主题创建对应的路由信息

1.2 发送高可用设计

发送端在发送主题的路由信息后，mq默认使用轮询算法进行路由的负载均衡。mq在发送时支持自定义的队列负载算法，这里使用的自定义的路由算法会导致重试机制失效。

1）mq发送时如果出现了失败，默认会重试两次

2）故障规避，当消息发送失败时，如果下一次消息还是发送到刚刚失败的broker上的话，其消息还是大概率会失败，所以为了保证重试的可靠性，在重试时会尽量避开刚刚接手失败的broker，而是选择其他broker上的队列进行发送，从而提高消息发送的成功率。

2、rocketmq消息

```java
class Message{
    private String topic;    //主题
    private int flag;        //消息标识
    private Map properties;    //tags:消息tag，用于消息过滤
                               //消息索引键，用空格隔开，mq可以根据key快速检索消息
                                //waitStoreMsgOK：消息发送是否等存储完再返回
    private byte[];    //消息体
}
```

3、生产者启动流程：

DefaultMQProducerImpl的start

```java
public void start(final boolean startFactory) throws MQClientException {
        switch (this.serviceState) {
            case CREATE_JUST:
                this.serviceState = ServiceState.START_FAILED;
                //检查配置，是否符合要求
                this.checkConfig();
                //将生产者的instanceName改为进程id
                if (!this.defaultMQProducer.getProducerGroup().equals(MixAll.CLIENT_INNER_PRODUCER_GROUP)) {
                    this.defaultMQProducer.changeInstanceNameToPID();
                }
 //创建MQClientInstance实例，整个jvm实例中只存在一个MQClientManager实例
 //维护一个MQClientInstance的缓存表ConcurrentMap<String/*clientId*/,MQClientInstance> 
 //同一个clientId只会创建一个MQClientInstance
                this.mQClientFactory = MQClientManager.getInstance().getOrCreateMQClientInstance(this.defaultMQProducer, rpcHook);
        //向MQInstance注册服务，将当前生产者加入MQClientInstance管理，方便进行网络请求、心跳管理
                boolean registerOK = mQClientFactory.registerProducer(this.defaultMQProducer.getProducerGroup(), this);
                if (!registerOK) {
                    this.serviceState = ServiceState.CREATE_JUST;
                    throw new MQClientException("The producer group[" + this.defaultMQProducer.getProducerGroup()
                        + "] has been created before, specify another name please." + FAQUrl.suggestTodo(FAQUrl.GROUP_NAME_DUPLICATE_URL),
                        null);
                }

                this.topicPublishInfoTable.put(this.defaultMQProducer.getCreateTopicKey(), new TopicPublishInfo());

                if (startFactory) {
                    mQClientFactory.start();
                }

                log.info("the producer [{}] start OK. sendMessageWithVIPChannel={}", this.defaultMQProducer.getProducerGroup(),
                    this.defaultMQProducer.isSendMessageWithVIPChannel());
                this.serviceState = ServiceState.RUNNING;
                break;
            case RUNNING:
            case START_FAILED:
            case SHUTDOWN_ALREADY:
                throw new MQClientException("The producer service state not OK, maybe started once, "
                    + this.serviceState
                    + FAQUrl.suggestTodo(FAQUrl.CLIENT_SERVICE_NOT_OK),
                    null);
            default:
                break;
        }

        this.mQClientFactory.sendHeartbeatToAllBrokerWithLock();

        RequestFutureHolder.getInstance().startScheduledTask(this);

    }
```

创建instance的函数：getOrCreateMQClientInstance

```java
public MQClientInstance getOrCreateMQClientInstance(final ClientConfig clientConfig, RPCHook rpcHook) {
        String clientId = clientConfig.buildMQClientId();
        MQClientInstance instance = this.factoryTable.get(clientId);
        if (null == instance) {
            instance =
                new MQClientInstance(clientConfig.cloneClientConfig(),
                    this.factoryIndexGenerator.getAndIncrement(), clientId, rpcHook);
            MQClientInstance prev = this.factoryTable.putIfAbsent(clientId, instance);
            if (prev != null) {
                instance = prev;
                log.warn("Returned Previous MQClientInstance for clientId:[{}]", clientId);
            } else {
                log.info("Created new MQClientInstance for clientId:[{}]", clientId);
            }
        }

        return instance;
    }
```

创建clientId：id是客户端IP+instance+unitname(可选)，instance为进程id以避免同一个机器中不同的进程互相影响。

同一个jvm中相同clientId的消费者和生产者在启动时获得到的mqClientInstance时同一个。

MQClientInstance封装了RocketMQ的网络处理API，是消息生产者、消息消费者与NameServer、Broker打交道的网络通道。

```java
public String buildMQClientId() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.getClientIP());

        sb.append("@");
        sb.append(this.getInstanceName());
        if (!UtilAll.isBlank(this.unitName)) {
            sb.append("@");
            sb.append(this.unitName);
        }

        if (enableStreamRequestType) {
            sb.append("@");
            sb.append(RequestType.STREAM);
        }

        return sb.toString();
    }
```

4、消息发送流程

1）消息长度验证，不能为空且消息大于0，且不能大于最大长度（默认4M)

2)查询主题路由信息tryTpFindTopicPublishInfo-》topic

发送前要获取主题的路由信息，才知道要发送到哪个broker。所以发送消息时会先查询本地缓存，如果本地缓存没有topic路由信息，则查询nameServer获取，然后更新本地路由信息表，并且生产者每隔30s从nameServer中更新路由表。

3）选择消息队列selectOneMessageQueue-》lastBrokerName

循环选择消息队列方法，路由表中消息队列个数取模，返回该位置的messageQueue  lastBrokerName：记录异常broker，下次选择时可跳过，提高消息发送成功率

①根据对消息队列进行轮询获取一个消息队列

②验证该消息队列是否可用

③如果返回的messageQueue可用，移除latencyFaultTolerance中该topic的条目，表明该broker故障已经恢复

4）发送消息sendKernelImpl

4.1 消息发送函数

```java
private SendResult sendDefaultImpl(
        Message msg,
        final CommunicationMode communicationMode,
        final SendCallback sendCallback,
        final long timeout
    ) throws MQClientException, RemotingException, MQBrokerException, InterruptedException             {
  //生产者自身状态校验
      this.makeSureStateOK();
      //校验消息属性：topic和长度等  
      Validators.checkMessage(msg, this.defaultMQProducer);
        final long invokeID = random.nextLong();
        long beginTimestampFirst = System.currentTimeMillis();
        long beginTimestampPrev = beginTimestampFirst;
        long endTimestamp = beginTimestampFirst;
    //查询topic的路由信息
        TopicPublishInfo topicPublishInfo = this.tryToFindTopicPublishInfo(msg.getTopic());
        if (topicPublishInfo != null && topicPublishInfo.ok()) {
            boolean callTimeout = false;
            MessageQueue mq = null;
            Exception exception = null;
            SendResult sendResult = null;
    //设置发送次数，异步发送的话时重试次数+1，否则是一次
            int timesTotal = communicationMode == CommunicationMode.SYNC ? 1 + this.defaultMQProducer.getRetryTimesWhenSendFailed() : 1;
            int times = 0;
            String[] brokersSent = new String[timesTotal];
            for (; times < timesTotal; times++) {
                String lastBrokerName = null == mq ? null : mq.getBrokerName();
                MessageQueue mqSelected = this.selectOneMessageQueue(topicPublishInfo, lastBrokerName);
                if (mqSelected != null) {
                    mq = mqSelected;
                    brokersSent[times] = mq.getBrokerName();
                    try {
                        beginTimestampPrev = System.currentTimeMillis();
                        if (times > 0) {
                            //Reset topic with namespace during resend.
                            msg.setTopic(this.defaultMQProducer.withNamespace(msg.getTopic()));
                        }
                //校验此时是否超时
                        long costTime = beginTimestampPrev - beginTimestampFirst;
                        if (timeout < costTime) {
                            callTimeout = true;
                            break;
                        }
                    //发送消息
                        sendResult = this.sendKernelImpl(msg, mq, communicationMode, sendCallback, topicPublishInfo, timeout - costTime);
                        endTimestamp = System.currentTimeMillis();
                        this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, false);
                        switch (communicationMode) {
                            case ASYNC:
                                return null;
                            case ONEWAY:
                                return null;
                            case SYNC:
                    //如果时异步的并且允许失败时候重试，则continue循环重试
                                if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                                    if (this.defaultMQProducer.isRetryAnotherBrokerWhenNotStoreOK()) {
                                        continue;
                                    }
                                }

                                return sendResult;
                            default:
                                break;
                        }
                    } catch (RemotingException e) {
                        endTimestamp = System.currentTimeMillis();
                        this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, true);
                        log.warn(String.format("sendKernelImpl exception, resend at once, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                        log.warn(msg.toString());
                        exception = e;
                        continue;
                    } catch (MQClientException e) {
                        endTimestamp = System.currentTimeMillis();
                        this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, true);
                        log.warn(String.format("sendKernelImpl exception, resend at once, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                        log.warn(msg.toString());
                        exception = e;
                        continue;
                    } catch (MQBrokerException e) {
                        endTimestamp = System.currentTimeMillis();
                        this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, true);
                        log.warn(String.format("sendKernelImpl exception, resend at once, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                        log.warn(msg.toString());
                        exception = e;
                        if (this.defaultMQProducer.getRetryResponseCodes().contains(e.getResponseCode())) {
                            continue;
                        } else {
                            if (sendResult != null) {
                                return sendResult;
                            }

                            throw e;
                        }
                    } catch (InterruptedException e) {
                        endTimestamp = System.currentTimeMillis();
                        this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, false);
                        log.warn(String.format("sendKernelImpl exception, throw exception, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                        log.warn(msg.toString());
                        throw e;
                    }
                } else {
                    break;
                }
            }
        //发送结果不为空则返回发送结果
            if (sendResult != null) {
                return sendResult;
            }

            String info = String.format("Send [%d] times, still failed, cost [%d]ms, Topic: %s, BrokersSent: %s",
                times,
                System.currentTimeMillis() - beginTimestampFirst,
                msg.getTopic(),
                Arrays.toString(brokersSent));

            info += FAQUrl.suggestTodo(FAQUrl.SEND_MSG_FAILED);

            MQClientException mqClientException = new MQClientException(info, exception);
            if (callTimeout) {
                throw new RemotingTooMuchRequestException("sendDefaultImpl call timeout");
            }

            if (exception instanceof MQBrokerException) {
                mqClientException.setResponseCode(((MQBrokerException) exception).getResponseCode());
            } else if (exception instanceof RemotingConnectException) {
                mqClientException.setResponseCode(ClientErrorCode.CONNECT_BROKER_EXCEPTION);
            } else if (exception instanceof RemotingTimeoutException) {
                mqClientException.setResponseCode(ClientErrorCode.ACCESS_BROKER_TIMEOUT);
            } else if (exception instanceof MQClientException) {
                mqClientException.setResponseCode(ClientErrorCode.BROKER_NOT_EXIST_EXCEPTION);
            }

            throw mqClientException;
        }

        validateNameServerSetting();

        throw new MQClientException("No route info of this topic: " + msg.getTopic() + FAQUrl.suggestTodo(FAQUrl.NO_TOPIC_ROUTE_INFO),
            null).setResponseCode(ClientErrorCode.NOT_FOUND_TOPIC_EXCEPTION);
    }
```

查询topic的路由信息tryToFindTopicPublishInfo。如果本地topic为空，则调用nameServer获取路由信息放到缓存。最后从缓存中获取路由信息

```java
private TopicPublishInfo tryToFindTopicPublishInfo(final String topic) {
        TopicPublishInfo topicPublishInfo = this.topicPublishInfoTable.get(topic);
        if (null == topicPublishInfo || !topicPublishInfo.ok()) {
            this.topicPublishInfoTable.putIfAbsent(topic, new TopicPublishInfo());
            this.mQClientFactory.updateTopicRouteInfoFromNameServer(topic);
            topicPublishInfo = this.topicPublishInfoTable.get(topic);
        }

        if (topicPublishInfo.isHaveTopicRouterInfo() || topicPublishInfo.ok()) {
            return topicPublishInfo;
        } else {
            this.mQClientFactory.updateTopicRouteInfoFromNameServer(topic, true, this.defaultMQProducer);
            topicPublishInfo = this.topicPublishInfoTable.get(topic);
            return topicPublishInfo;
        }
    }
```

```java
class TopicPublishInfo{
//是否是顺序消息
    private boolean orderTopic=false;    
   //
    private boolean havetopicRouteInfo=false;
    //该主题队列的消息队列
    private List messageQueueList;
    //每选择一次消息队列，这个值就会自增1，如果超过Integer.MAX_VALUE,
    //则重置为0，用于选择队列
    private volatile ThreadLocalIndex sendWhichQueue;
    private TopicRouteData topicRouteData;
}

class TopicRouteData{
    private String orderTopicConf;
//topic队列元数据
    private List queueDatas;
//topic分布的broker数据
    private List brokerDatas;
//broker上过滤服务器的地址列表
    private HashMap filterServerTable；
}
```

updateTopicRouteInfoFromNameServer

```java
TopicRouteData topicRouteData;
//如果defalut为true，则此时是使用默认主题查询。
//查到的话把读写队列的个数替换为消息生产者默认的队列个数。
if (isDefault && defaultMQProducer != null) {
    topicRouteData = this.mQClientAPIImpl.getDefaultTopicRouteInfoFromNameServer(defaultMQProducer.getCreateTopicKey(),
        clientConfig.getMqClientApiTimeout());
    if (topicRouteData != null) {
        for (QueueData data : topicRouteData.getQueueDatas()) {
            int queueNums = Math.min(defaultMQProducer.getDefaultTopicQueueNums(), data.getReadQueueNums());
            data.setReadQueueNums(queueNums);
            data.setWriteQueueNums(queueNums);
        }
    }
} else {
//如果defalut为false的情况是使用参数topic查询，
//如果每查到的话则返回false，表示路由信息为变化
    topicRouteData = this.mQClientAPIImpl.getTopicRouteInfoFromNameServer(topic, clientConfig.getMqClientApiTimeout());
}
if (topicRouteData != null) {
    //如果找到了路由信息的话，则对比本地缓存，判断是否路由变化，未变化的话直接返回
    TopicRouteData old = this.topicRouteTable.get(topic);
    boolean changed = topicRouteData.topicRouteDataChanged(old);
    if (!changed) {
        changed = this.isNeedUpdateTopicRouteInfo(topic);
    } else {
        log.info("the topic[{}] route info changed, old[{}] ,new[{}]", topic, old, topicRouteData);
    }

    if (changed) {

        for (BrokerData bd : topicRouteData.getBrokerDatas()) {
            this.brokerAddrTable.put(bd.getBrokerName(), bd.getBrokerAddrs());
        }

        // Update endpoint map
        {
            ConcurrentMap<MessageQueue, String> mqEndPoints = topicRouteData2EndpointsForStaticTopic(topic, topicRouteData);
            if (!mqEndPoints.isEmpty()) {
                topicEndPointsTable.put(topic, mqEndPoints);
            }
        }

        // Update Pub info
        //更新MQClientInstance Broker地址缓存表
        {
            TopicPublishInfo publishInfo = topicRouteData2TopicPublishInfo(topic, topicRouteData);
            publishInfo.setHaveTopicRouterInfo(true);
            for (Entry<String, MQProducerInner> entry : this.producerTable.entrySet()) {
                MQProducerInner impl = entry.getValue();
                if (impl != null) {
                    impl.updateTopicPublishInfo(topic, publishInfo);
                }
            }
        }

        // Update sub info
        if (!consumerTable.isEmpty()) {
            Set<MessageQueue> subscribeInfo = topicRouteData2TopicSubscribeInfo(topic, topicRouteData);
            for (Entry<String, MQConsumerInner> entry : this.consumerTable.entrySet()) {
                MQConsumerInner impl = entry.getValue();
                if (impl != null) {
                    impl.updateTopicSubscribeInfo(topic, subscribeInfo);
                }
            }
        }
        TopicRouteData cloneTopicRouteData = new TopicRouteData(topicRouteData);
        log.info("topicRouteTable.put. Topic = {}, TopicRouteData[{}]", topic, cloneTopicRouteData);
        this.topicRouteTable.put(topic, cloneTopicRouteData);
        return true;
    }
} else {
    log.warn("updateTopicRouteInfoFromNameServer, getTopicRouteInfoFromNameServer return null, Topic: {}. [{}]", topic, this.clientId);
}
} catch (MQClientException e) {
if (!topic.startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX) && !topic.equals(TopicValidator.AUTO_CREATE_TOPIC_KEY_TOPIC)) {
    log.warn("updateTopicRouteInfoFromNameServer Exception", e);
}
} catch (RemotingException e) {
log.error("updateTopicRouteInfoFromNameServer Exception", e);
throw new IllegalStateException(e);
} finally {
this.lockNamesrv.unlock();
}finally {
                    this.lockNamesrv.unlock();
                }
```

topicRouteData2TopicPublishInfo：将topicRouteData的List<QueueData>转换成TopicPublishInfo里的List<MessageQueue>,具体实现在这个函数中，然后再更新MQClientInstance管辖的消息，发送关于该topic的路由信息

```java
public static TopicPublishInfo topicRouteData2TopicPublishInfo(final String topic, final TopicRouteData route) {
        TopicPublishInfo info = new TopicPublishInfo();
        // TO DO should check the usage of raw route, it is better to remove such field
        info.setTopicRouteData(route);
        if (route.getOrderTopicConf() != null && route.getOrderTopicConf().length() > 0) {
            String[] brokers = route.getOrderTopicConf().split(";");
            for (String broker : brokers) {
                String[] item = broker.split(":");
                int nums = Integer.parseInt(item[1]);
                for (int i = 0; i < nums; i++) {
                    MessageQueue mq = new MessageQueue(topic, item[0], i);
                    info.getMessageQueueList().add(mq);
                }
            }

            info.setOrderTopic(true);
        } else if (route.getOrderTopicConf() == null
                && route.getTopicQueueMappingByBroker() != null
                && !route.getTopicQueueMappingByBroker().isEmpty()) {
            info.setOrderTopic(false);
            ConcurrentMap<MessageQueue, String> mqEndPoints = topicRouteData2EndpointsForStaticTopic(topic, route);
            info.getMessageQueueList().addAll(mqEndPoints.keySet());
            info.getMessageQueueList().sort((mq1, mq2) -> MixAll.compareInteger(mq1.getQueueId(), mq2.getQueueId()));
        } else {
            List<QueueData> qds = route.getQueueDatas();
            Collections.sort(qds);
            for (QueueData qd: qds) {
        //循环遍历queueData信息，如果没有写权限，则遍历下一个
                if (PermName.isWriteable(qd.getPerm())) {
                    BrokerData brokerData = null;
                //根据名称查找对应的
                    for (BrokerData bd : route.getBrokerDatas()) {
                        if (bd.getBrokerName().equals(qd.getBrokerName())) {
                            brokerData = bd;
                            break;
                        }
                    }

                    if (null == brokerData) {
                        continue;
                    }
                //如果每找到主节点，则查找下一个queueData、
                    if (!brokerData.getBrokerAddrs().containsKey(MixAll.MASTER_ID)) {
                        continue;
                    }

                    for (int i = 0; i < qd.getWriteQueueNums(); i++) {
                        MessageQueue mq = new MessageQueue(topic, qd.getBrokerName(), i);
                        info.getMessageQueueList().add(mq);
                    }
                }
            }

            info.setOrderTopic(false);
        }

        return info;
    }
```

4.3    选择消息队列

假如创建了消息队列，分别再broker-a、broker-b上各创建了四个的话，这时候返回的消息队列分别为[{"brokerName":"broker-a"、"queueId":0}、{"brokerName":"broker-a"、"queueId":1}、{"brokerName":"broker-a"、"queueId":2}、{"brokerName":"broker-a"、"queueId":3}、{"brokerName":"broker-b"、"queueId":0}、{"brokerName":"broker-b"、"queueId":1}、{"brokerName":"broker-b"、"queueId":2}、{"brokerName":"broker-b"、"queueId":3}、]

选择一个消息队列发送消息，失败则重试。

1）轮询获取一个消息队列

2）验证消息队列是否可用

3）如果返回的消息队列可用，则移除latencyFaultTolerance中的该topic的条目，表明该broker已恢复

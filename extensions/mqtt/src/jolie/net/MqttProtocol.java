/*
 * Copyright (C) 2017 stefanopiozingaro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either mqttVersion 3 of the License, or
 * (at your option) any later mqttVersion.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jolie.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttSubscribePayload;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.codec.mqtt.MqttVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.ThreadLocalRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import jolie.net.protocols.AsyncCommProtocol;
import jolie.runtime.VariablePath;

/**
 * Implementation of the { @link AsyncCommProtocol } for MQTT protocol relying
 * on TCP/IP socket, uses netty and Non blocking Sockets
 *
 * TODO Modificare { @link CommCore} 1. in caso di una InputPort: 1.1 in caso di
 * protocollo PublishSubscribeProtocol (e.g. MqttProtocol extends
 * PublishSubscribeProtocol) si dovrà creare un CommChannel (che rimarrà aperto)
 * 2.1 altrimenti creò SocketListener e faccio la solita roba
 *
 * @author stefanopiozingaro
 */
public class MqttProtocol extends AsyncCommProtocol {

    private final boolean inInputPort;
    private final int keepAliveConnectTimeSeconds;
    private MqttVersion mqttVersion;
    private String willTopic;
    private String willMessage;
    private String mqttUserName;
    private String mqttPassword;
    private final List<MqttPublishMessage> pendingPublishes;
    private final List<MqttSubscribeMessage> pendingSubscriptions;
    private final Map<String, PublishHandler> subscriptions;
    private boolean publishReady;
    private boolean subscribeReady;
    private Channel connectedChannel;

    public Map<String, PublishHandler> getSubscriptions() {
        return subscriptions;
    }

    public Channel getConnectedChannel() {
        return connectedChannel;
    }

    public void setConnectedChannel(Channel connectedChannel) {
        this.connectedChannel = connectedChannel;
    }

    public boolean isInInputPort() {
        return inInputPort;
    }

    public MqttVersion getVersion() {
        return mqttVersion;
    }

    public void setVersion(MqttVersion version) {
        this.mqttVersion = version;
    }

    public String getWillTopic() {
        return willTopic;
    }

    public void setWillTopic(String willTopic) {
        this.willTopic = willTopic;
    }

    public String getWillMessage() {
        return willMessage;
    }

    public void setWillMessage(String willMessage) {
        this.willMessage = willMessage;
    }

    public String getUserName() {
        return mqttUserName;
    }

    public void setUserName(String userName) {
        this.mqttUserName = userName;
    }

    public String getPassword() {
        return mqttPassword;
    }

    public void setPassword(String password) {
        this.mqttPassword = password;
    }

    public int getKeepAliveConnectTimeSeconds() {
        return keepAliveConnectTimeSeconds;
    }

    public List<MqttPublishMessage> getPendingPublishes() {
        return pendingPublishes;
    }

    public List<MqttSubscribeMessage> getPendingSubscriptions() {
        return pendingSubscriptions;
    }

    /**
     * Default Constructor for MqttProtocol going super Look at the { @link
     * HttpProtocol.java} one
     *
     * @param inInputPort
     * @param configurationPath
     */
    public MqttProtocol(boolean inInputPort, VariablePath configurationPath) {
        super(configurationPath);
        this.pendingPublishes = new ArrayList<>();
        this.pendingSubscriptions = new ArrayList<>();
        this.subscriptions = new HashMap<>();
        this.inInputPort = inInputPort;
        this.publishReady = false;
        this.subscribeReady = false;
        this.keepAliveConnectTimeSeconds = 2;
    }

    /**
     * To buildPublication, just take a future MqttMessage object TODO implement
     * msgToMqttMsgCodec()
     *
     * @param topic String
     * @param message MqttMessage
     * @return
     */
    public MqttPublishMessage buildPublication(String topic, String message) {

        boolean isDup = Boolean.FALSE;
        MqttQoS publishQoS = MqttQoS.AT_LEAST_ONCE;
        boolean isConnectRetain = Boolean.FALSE;
        int messageId = (int) (Math.random() * 65536);

        MqttFixedHeader mqttFixedHeader = new MqttFixedHeader(
                MqttMessageType.PUBLISH,
                isDup,
                publishQoS,
                isConnectRetain,
                0
        );

        MqttPublishVariableHeader variableHeader = new MqttPublishVariableHeader(topic, messageId);

        ByteBuf payload = parseObject(message);

        MqttPublishMessage mpm = new MqttPublishMessage(
                mqttFixedHeader,
                variableHeader,
                payload
        );

        if (this.publishReady) {
            this.connectedChannel.writeAndFlush(mpm);
        } else {
            this.pendingPublishes.add(mpm);
        }

        return mpm;
    }

    /**
     * To buildPublication, just take a future MqttMessage object TODO implement
     * msgToMqttMsgCodec()
     *
     * @param topics List of MqttTopicSubscription
     * @param handler PublishHandler
     * @return
     */
    public MqttSubscribeMessage buildSubscription(List<MqttTopicSubscription> topics, PublishHandler handler) {

        boolean isDup = Boolean.FALSE;
        MqttQoS subscribeQoS = MqttQoS.AT_LEAST_ONCE;
        boolean isSubscribeRetain = Boolean.FALSE;
        int messageId = ThreadLocalRandom.current().nextInt(1, 65536 + 1);

        MqttFixedHeader mqttFixedHeader = new MqttFixedHeader(
                MqttMessageType.SUBSCRIBE,
                isDup,
                subscribeQoS,
                isSubscribeRetain,
                0
        );

        MqttMessageIdVariableHeader variableHeader
                = MqttMessageIdVariableHeader.from(messageId);

        MqttSubscribePayload payload = new MqttSubscribePayload(topics);

        MqttSubscribeMessage msm
                = new MqttSubscribeMessage(mqttFixedHeader, variableHeader, payload);

        // write immeditely on the channel or store it
        if (this.subscribeReady) {
            this.connectedChannel.writeAndFlush(msm).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        for (ListIterator<MqttTopicSubscription> i = topics.listIterator(); i.hasNext();) {
                            subscriptions.put(i.next().topicName(), handler);
                        }
                    }
                }
            });
        } else {
            this.pendingSubscriptions.add(msm);
            for (ListIterator<MqttTopicSubscription> i = topics.listIterator(); i.hasNext();) {
                subscriptions.put(i.next().topicName(), handler);
            }
        }

        return msm;
    }

    /**
     *
     * @param channel
     */
    public void sendAndFlush(Channel channel) {

        if (channel.isActive() && channel.isWritable()) {

            if (inInputPort) {

                this.subscribeReady = true;
                for (ListIterator<MqttSubscribeMessage> i = this.pendingSubscriptions.listIterator(); i.hasNext();) {
                    channel.writeAndFlush(i.next());
                    i.remove();
                }
            } else {

                this.publishReady = true;
                for (ListIterator<MqttPublishMessage> j = this.pendingPublishes.listIterator(); j.hasNext();) {
                    channel.writeAndFlush(j.next());
                    j.remove();
                }
            }
        }
    }

    /*
     * Inner class Parameters copied from { @link HttpProtocol }
     * Since now (01/06/2017) we use only the concurrent param
     */
    private static class Parameters {

        private static final String CONCURRENT = "concurrent";

    }

    /**
     * Method overrinding setupPipeline of { @link AsyncCommProtocol } Default
     * pipeline for Mqtt use Encoder and Decoder, we added
     * MqttProtocolInboundHandler
     *
     * @param pipeline the pipeline to fill with specific protocol handlers
     */
    @Override
    public void setupPipeline(ChannelPipeline pipeline) {

        pipeline.addLast("MqttDecoder", new MqttDecoder());
        pipeline.addLast("MqttEncoder", MqttEncoder.INSTANCE);
        pipeline.addLast("MqttPublishSubscribe", new MqttHandler(this));
        pipeline.addLast("Ping", new MqttPingHandler());
    }

    /**
     *
     * @return the name of the protocol, in which case is mqtt
     */
    @Override
    public String name() {
        return "mqtt";
    }

    /**
     *
     * @return if the behaviour is concurrent or not (i guess)
     * @author stefanopiozingaro
     */
    @Override
    public boolean isThreadSafe() {
        return checkBooleanParameter(Parameters.CONCURRENT);
    }

    /**
     * TODO parse object according to object type passed
     *
     * @param message
     * @return ByteBuf
     */
    private ByteBuf parseObject(String message) {
        return Unpooled.copiedBuffer(message.getBytes(CharsetUtil.UTF_8));
    }

    /**
     * Futuristic Method
     * @param mqttMsg {@link MqttMessage}
     * @return {@link CommMessage}
     */
    private CommMessage MqttMsg2CommMsgCodec(MqttMessage mqttMsg) {
        CommMessage commMsg = null;
        return commMsg;

    }

    /**
     * Futuristic Method
     * @param commMsg {@link CommMessage}
     * @return {@link MqttMessage}
     */
    private MqttMessage CommMsg2MqttMsgCodec(CommMessage commMsg) {
        MqttMessage mqttMsg = null;
        return mqttMsg;
    }

}

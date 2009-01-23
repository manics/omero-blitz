/*
 *   $Id$
 *
 *   Copyright 2008 Glencoe Software, Inc. All rights reserved.
 *   Use is subject to license terms supplied in LICENSE.txt
 */

package ome.services.blitz.fire;

import java.lang.reflect.Method;

import omero.ApiUsageException;
import omero.InternalException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

import IceStorm.AlreadySubscribed;
import IceStorm.BadQoS;
import IceStorm.NoSuchTopic;

/**
 * Local dispatcher to {@link IceStorm.TopicManager}
 *
 * @author Josh Moore, josh at glencoesoftware.com
 * @since December 2008
 */
public final class TopicManager implements ApplicationListener {

    public final static class TopicMessage extends ApplicationEvent {

        private final String topic;
        private final Ice.ObjectPrxHelperBase base;
        private final String method;
        private final Object[] args;

        public TopicMessage(Object source, String topic,
                Ice.ObjectPrxHelperBase base, String method, Object... args) {
            super(source);
            this.topic = topic;
            this.base = base;
            this.method = method;
            this.args = args;
        }
    }

    private final static Log log = LogFactory.getLog(TopicManager.class);

    private final Ice.Communicator communicator;

    public TopicManager(Ice.Communicator communicator) {
        this.communicator = communicator;
    }

    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof TopicMessage) {
            if (topicManager == null) {
                log.warn("No topic manager");
                return;
            }
            TopicMessage msg = (TopicMessage) event;
            try {
                Ice.ObjectPrx obj = publisherOrNull(msg.topic);
                msg.base.__copyFrom(obj);
                Method m = msg.base.getClass().getMethod(msg.method);
                m.invoke(msg.base, msg.args);
            } catch (Exception e) {
                log.error("Error publishing to topic:" + msg.topic, e);
            }
        }
    }

    /**
     * Enforces <em>no</em> security constraints. For the moment, that is the
     * responsibility of application code. WILL CHANGE>
     */
    public void register(String topicName, Ice.ObjectPrx prx)
            throws omero.ServerError {
        String id = prx.ice_id();
        id = id.replaceFirst("::", "");
        id = id.replace("::", ".");
        id = id + "PrxHelper";
        Class<?> pubClass = null;
        try {
            pubClass = Class.forName(id);
        } catch (ClassNotFoundException e) {
            throw new ApiUsageException(null, null, "Unknown type for proxy: "
                    + prx.ice_id());
        }
        IceStorm.TopicPrx topic = topicOrNull(topicName);

        while (topic != null) { // See 45.7.3 IceStorm Clients under HA IceStorm
            try {
                topic.subscribeAndGetPublisher(null, prx);
            } catch (Ice.UnknownException ue) {
                log.warn("Unknown exception on subscribeAndGetPublisher");
                continue;
            } catch (AlreadySubscribed e) {
                throw new ApiUsageException(null, null,
                        "Proxy already subscribed: " + prx);
            } catch (BadQoS e) {
                throw new InternalException(null, null,
                        "BadQos in TopicManager.subscribe");
            }
            break;
        }
    }

    // Helpers
    // =========================================================================

    protected IceStorm.TopicManagerPrx managerOrNull() {

        Ice.ObjectPrx objectPrx = communicator.stringToProxy("IceGrid/Query");
        Ice.ObjectPrx[] candidates = null;

        try {
            IceGrid.QueryPrx query = IceGrid.QueryPrxHelper
                    .checkedCast(objectPrx);
            candidates = query.findAllObjectsByType("::IceStorm::TopicManager");
        } catch (Exception e) {
            log.warn("Error querying for topic manager", e);
        }

        IceStorm.TopicManagerPrx tm = null;

        if (candidates == null || candidates.length == 0) {
            log.warn("Found no topic manager");
        } else if (candidates.length > 1) {
            log.warn("Found wrong number of topic managers: "
                    + candidates.length);
        } else {
            try {
                tm = IceStorm.TopicManagerPrxHelper.checkedCast(candidates[0]);
            } catch (Exception e) {
                log.warn("Could not cast to TopicManager", e);
            }
        }
        return tm;
    }

    protected IceStorm.TopicPrx topicOrNull(String name) {
	IceStorm.TopicManager topicManager = lookupTopicManagerOrNull();
        IceStorm.TopicPrx topic = null;
	if (topicManager != null) {
	    try {
		topic = topicManager.create(name);
	    } catch (IceStorm.TopicExists ex2) {
		try {
		    topic = topicManager.retrieve(name);
		} catch (NoSuchTopic e) {
		    throw new RuntimeException("Race condition retriving topic: "
					       + name);
		}
	    }
        }
        return topic;
    }

    protected Ice.ObjectPrx publisherOrNull(String name) {
        IceStorm.TopicPrx topic = createOrRetrieveTopic(name);
        Ice.ObjectPrx pub = null;
        if (topic != null) {
            pub = topic.getPublisher().ice_oneway();
        }
        return pub;
    }

}
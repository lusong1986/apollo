package com.ctrip.framework.apollo.configservice.controller;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import com.ctrip.framework.apollo.biz.config.BizConfig;
import com.ctrip.framework.apollo.biz.entity.Release;
import com.ctrip.framework.apollo.biz.entity.ReleaseMessage;
import com.ctrip.framework.apollo.biz.message.ReleaseMessageListener;
import com.ctrip.framework.apollo.biz.message.Topics;
import com.ctrip.framework.apollo.biz.service.ReleaseService;
import com.ctrip.framework.apollo.biz.utils.EntityManagerUtil;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.configservice.service.ReleaseMessageServiceWithCache;
import com.ctrip.framework.apollo.configservice.util.NamespaceUtil;
import com.ctrip.framework.apollo.configservice.util.WatchKeysUtil;
import com.ctrip.framework.apollo.configservice.wrapper.DeferredResultWrapper;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.apollo.core.dto.ApolloConfigNotification;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
@RestController
@RequestMapping("/notifications/v2")
public class NotificationControllerV2 implements ReleaseMessageListener {
	private static final Logger logger = LoggerFactory.getLogger(NotificationControllerV2.class);

	private final Multimap<String, DeferredResultWrapper> deferredResults = Multimaps
			.synchronizedSetMultimap(HashMultimap.create());
	private static final Splitter STRING_SPLITTER = Splitter.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR)
			.omitEmptyStrings();
	private static final Joiner STRING_JOINER = Joiner.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR);
	private static final int INSTANCE_CONFIG_CACHE_MAX_SIZE = 50000;
	private static final Type notificationsTypeReference = new TypeToken<List<ApolloConfigNotification>>() {
	}.getType();

	private final ExecutorService largeNotificationBatchExecutorService;

	private Cache<String, String> instanceConfigActiveCache;

	@Autowired
	private WatchKeysUtil watchKeysUtil;

	@Autowired
	private ReleaseMessageServiceWithCache releaseMessageService;

	@Autowired
	private EntityManagerUtil entityManagerUtil;

	@Autowired
	private NamespaceUtil namespaceUtil;

	@Autowired
	private Gson gson;

	@Autowired
	private BizConfig bizConfig;

	@Autowired
	private ReleaseService releaseService;

	public NotificationControllerV2() {
		largeNotificationBatchExecutorService = Executors.newSingleThreadExecutor(ApolloThreadFactory.create(
				"NotificationControllerV2", true));
		instanceConfigActiveCache = CacheBuilder.newBuilder().expireAfterWrite(70, TimeUnit.SECONDS)
				.maximumSize(INSTANCE_CONFIG_CACHE_MAX_SIZE).build();
	}

	@RequestMapping(value = "/queryactive", method = RequestMethod.GET)
	public Boolean queryActiveInstance(@RequestParam String appId, @RequestParam String clusterName,
			@RequestParam long releaseId, @RequestParam(value = "dataCenter", required = false) String dataCenter,
			@RequestParam(value = "ip", required = true) String clientIp, HttpServletRequest request,
			HttpServletResponse response) throws IOException {

		Release release = releaseService.findActiveOne(releaseId);
		if (null == release) {
			logger.info("Can not get release for releaseId:" + releaseId);
			return true;
		}

		Set<String> assembleWatchKeys = assembleWatchKeys(appId, clusterName, release.getNamespaceName(), dataCenter);
		for (String key : assembleWatchKeys) {
			String value = instanceConfigActiveCache.getIfPresent(key + "+" + clientIp);
			if (null != value) {
				return true;
			}
		}

		return false;
	}

	@RequestMapping(value = "/queryactiveByNamespace", method = RequestMethod.GET)
	public Boolean queryActiveInstanceByNamespaceName(@RequestParam String appId, @RequestParam String clusterName,
			@RequestParam String namespaceName,
			@RequestParam(value = "dataCenter", required = false) String dataCenter,
			@RequestParam(value = "ip", required = true) String clientIp, HttpServletRequest request,
			HttpServletResponse response) throws IOException {

		Set<String> assembleWatchKeys = assembleWatchKeys(appId, clusterName, namespaceName, dataCenter);
		for (String key : assembleWatchKeys) {
			String value = instanceConfigActiveCache.getIfPresent(key + "+" + clientIp);
			if (null != value) {
				return true;
			}
		}

		return false;
	}

	@RequestMapping(method = RequestMethod.GET)
	public DeferredResult<ResponseEntity<List<ApolloConfigNotification>>> pollNotification(
			@RequestParam(value = "appId") String appId, @RequestParam(value = "cluster") String cluster,
			@RequestParam(value = "notifications") String notificationsAsString,
			@RequestParam(value = "dataCenter", required = false) String dataCenter,
			@RequestParam(value = "ip", required = false) String clientIp) {
		List<ApolloConfigNotification> notifications = null;

		try {
			notifications = gson.fromJson(notificationsAsString, notificationsTypeReference);
		} catch (Throwable ex) {
			Tracer.logError(ex);
		}

		if (CollectionUtils.isEmpty(notifications)) {
			throw new BadRequestException("Invalid format of notifications: " + notificationsAsString);
		}

		DeferredResultWrapper deferredResultWrapper = new DeferredResultWrapper();
		Set<String> namespaces = Sets.newHashSet();
		Map<String, Long> clientSideNotifications = Maps.newHashMap();
		Map<String, ApolloConfigNotification> filteredNotifications = filterNotifications(appId, notifications);

		for (Map.Entry<String, ApolloConfigNotification> notificationEntry : filteredNotifications.entrySet()) {
			String normalizedNamespace = notificationEntry.getKey();
			ApolloConfigNotification notification = notificationEntry.getValue();
			namespaces.add(normalizedNamespace);
			clientSideNotifications.put(normalizedNamespace, notification.getNotificationId());
			if (!Objects.equals(notification.getNamespaceName(), normalizedNamespace)) {
				deferredResultWrapper.recordNamespaceNameNormalizedResult(notification.getNamespaceName(),
						normalizedNamespace);
			}
		}

		if (CollectionUtils.isEmpty(namespaces)) {
			throw new BadRequestException("Invalid format of notifications: " + notificationsAsString);
		}

		Multimap<String, String> watchedKeysMap = watchKeysUtil.assembleAllWatchKeys(appId, cluster, namespaces,
				dataCenter);

		Set<String> watchedKeys = Sets.newHashSet(watchedKeysMap.values());

		for (String key : watchedKeys) {
			instanceConfigActiveCache.put(key + "+" + clientIp, "active");
		}

		List<ReleaseMessage> latestReleaseMessages = releaseMessageService
				.findLatestReleaseMessagesGroupByMessages(watchedKeys);

		/**
		 * Manually close the entity manager. Since for async request, Spring won't do so until the request is finished,
		 * which is unacceptable since we are doing long polling - means the db connection would be hold for a very long
		 * time
		 */
		entityManagerUtil.closeEntityManager();

		List<ApolloConfigNotification> newNotifications = getApolloConfigNotifications(namespaces,
				clientSideNotifications, watchedKeysMap, latestReleaseMessages);

		if (!CollectionUtils.isEmpty(newNotifications)) {
			deferredResultWrapper.setResult(newNotifications);
		} else {
			// register all keys
			for (String key : watchedKeys) {
				this.deferredResults.put(key, deferredResultWrapper);
			}

			deferredResultWrapper.onTimeout(() -> logWatchedKeys(watchedKeys, "Apollo.LongPoll.TimeOutKeys"));

			deferredResultWrapper.onCompletion(() -> {
				// unregister all keys
					for (String key : watchedKeys) {
						deferredResults.remove(key, deferredResultWrapper);
					}
					logWatchedKeys(watchedKeys, "Apollo.LongPoll.CompletedKeys");
				});

			logWatchedKeys(watchedKeys, "Apollo.LongPoll.RegisteredKeys");
			logger.debug("Listening {} from appId: {}, cluster: {}, namespace: {}, datacenter: {}", watchedKeys, appId,
					cluster, namespaces, dataCenter);
		}

		return deferredResultWrapper.getResult();
	}

	private Map<String, ApolloConfigNotification> filterNotifications(String appId,
			List<ApolloConfigNotification> notifications) {
		Map<String, ApolloConfigNotification> filteredNotifications = Maps.newHashMap();
		for (ApolloConfigNotification notification : notifications) {
			if (Strings.isNullOrEmpty(notification.getNamespaceName())) {
				continue;
			}
			// strip out .properties suffix
			String originalNamespace = namespaceUtil.filterNamespaceName(notification.getNamespaceName());
			notification.setNamespaceName(originalNamespace);
			// fix the character case issue, such as FX.apollo <-> fx.apollo
			String normalizedNamespace = namespaceUtil.normalizeNamespace(appId, originalNamespace);

			// in case client side namespace name has character case issue and has difference notification ids
			// such as FX.apollo = 1 but fx.apollo = 2, we should let FX.apollo have the chance to update its
			// notification id
			// which means we should record FX.apollo = 1 here and ignore fx.apollo = 2
			if (filteredNotifications.containsKey(normalizedNamespace)
					&& filteredNotifications.get(normalizedNamespace).getNotificationId() < notification
							.getNotificationId()) {
				continue;
			}

			filteredNotifications.put(normalizedNamespace, notification);
		}
		return filteredNotifications;
	}

	private List<ApolloConfigNotification> getApolloConfigNotifications(Set<String> namespaces,
			Map<String, Long> clientSideNotifications, Multimap<String, String> watchedKeysMap,
			List<ReleaseMessage> latestReleaseMessages) {
		List<ApolloConfigNotification> newNotifications = Lists.newArrayList();
		if (!CollectionUtils.isEmpty(latestReleaseMessages)) {
			Map<String, Long> latestNotifications = Maps.newHashMap();
			for (ReleaseMessage releaseMessage : latestReleaseMessages) {
				latestNotifications.put(releaseMessage.getMessage(), releaseMessage.getId());
			}

			for (String namespace : namespaces) {
				long clientSideId = clientSideNotifications.get(namespace);
				long latestId = ConfigConsts.NOTIFICATION_ID_PLACEHOLDER;
				Collection<String> namespaceWatchedKeys = watchedKeysMap.get(namespace);
				for (String namespaceWatchedKey : namespaceWatchedKeys) {
					long namespaceNotificationId = latestNotifications.getOrDefault(namespaceWatchedKey,
							ConfigConsts.NOTIFICATION_ID_PLACEHOLDER);
					if (namespaceNotificationId > latestId) {
						latestId = namespaceNotificationId;
					}
				}
				if (latestId > clientSideId) {
					ApolloConfigNotification notification = new ApolloConfigNotification(namespace, latestId);
					namespaceWatchedKeys
							.stream()
							.filter(latestNotifications::containsKey)
							.forEach(
									namespaceWatchedKey -> notification.addMessage(namespaceWatchedKey,
											latestNotifications.get(namespaceWatchedKey)));
					newNotifications.add(notification);
				}
			}
		}
		return newNotifications;
	}

	@Override
	public void handleMessage(ReleaseMessage message, String channel) {
		logger.info("message received - channel: {}, message: {}", channel, message);

		String content = message.getMessage();
		Tracer.logEvent("Apollo.LongPoll.Messages", content);
		if (!Topics.APOLLO_RELEASE_TOPIC.equals(channel) || Strings.isNullOrEmpty(content)) {
			return;
		}

		String changedNamespace = retrieveNamespaceFromReleaseMessage.apply(content);

		if (Strings.isNullOrEmpty(changedNamespace)) {
			logger.error("message format invalid - {}", content);
			return;
		}

		if (!deferredResults.containsKey(content)) {
			return;
		}

		// create a new list to avoid ConcurrentModificationException
		List<DeferredResultWrapper> results = Lists.newArrayList(deferredResults.get(content));

		ApolloConfigNotification configNotification = new ApolloConfigNotification(changedNamespace, message.getId());
		configNotification.addMessage(content, message.getId());

		// do async notification if too many clients
		if (results.size() > bizConfig.releaseMessageNotificationBatch()) {
			largeNotificationBatchExecutorService.submit(() -> {
				logger.debug("Async notify {} clients for key {} with batch {}", results.size(), content,
						bizConfig.releaseMessageNotificationBatch());
				for (int i = 0; i < results.size(); i++) {
					if (i > 0 && i % bizConfig.releaseMessageNotificationBatch() == 0) {
						try {
							TimeUnit.MILLISECONDS.sleep(bizConfig.releaseMessageNotificationBatchIntervalInMilli());
						} catch (InterruptedException e) {
							// ignore
				}
			}
			logger.debug("Async notify {}", results.get(i));
			results.get(i).setResult(configNotification);
		}
	})		;
			return;
		}

		logger.debug("Notify {} clients for key {}", results.size(), content);

		for (DeferredResultWrapper result : results) {
			result.setResult(configNotification);
		}
		logger.debug("Notification completed");
	}

	private static final Function<String, String> retrieveNamespaceFromReleaseMessage = releaseMessage -> {
		if (Strings.isNullOrEmpty(releaseMessage)) {
			return null;
		}
		List<String> keys = STRING_SPLITTER.splitToList(releaseMessage);
		// message should be appId+cluster+namespace
		if (keys.size() != 3) {
			logger.error("message format invalid - {}", releaseMessage);
			return null;
		}
		return keys.get(2);
	};

	private void logWatchedKeys(Set<String> watchedKeys, String eventName) {
		for (String watchedKey : watchedKeys) {
			Tracer.logEvent(eventName, watchedKey);
		}
	}

	private String assembleKey(String appId, String cluster, String namespace) {
		return STRING_JOINER.join(appId, cluster, namespace);
	}

	private Set<String> assembleWatchKeys(String appId, String clusterName, String namespace, String dataCenter) {
		if (ConfigConsts.NO_APPID_PLACEHOLDER.equalsIgnoreCase(appId)) {
			return Collections.emptySet();
		}
		Set<String> watchedKeys = Sets.newHashSet();

		// watch specified cluster config change
		if (!Objects.equals(ConfigConsts.CLUSTER_NAME_DEFAULT, clusterName)) {
			watchedKeys.add(assembleKey(appId, clusterName, namespace));
		}

		// watch data center config change
		if (!Strings.isNullOrEmpty(dataCenter) && !Objects.equals(dataCenter, clusterName)) {
			watchedKeys.add(assembleKey(appId, dataCenter, namespace));
		}

		// watch default cluster config change
		watchedKeys.add(assembleKey(appId, ConfigConsts.CLUSTER_NAME_DEFAULT, namespace));

		return watchedKeys;
	}

}

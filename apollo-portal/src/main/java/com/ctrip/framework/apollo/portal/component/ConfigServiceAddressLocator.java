package com.ctrip.framework.apollo.portal.component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.HttpMessageConverters;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import com.ctrip.framework.apollo.core.MetaDomainConsts;
import com.ctrip.framework.apollo.core.dto.ServiceDTO;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.google.common.collect.Lists;

@Component
public class ConfigServiceAddressLocator {
	
	private static final Logger logger = LoggerFactory.getLogger(ConfigServiceAddressLocator.class);

	private static final long NORMAL_REFRESH_INTERVAL = 5 * 60 * 1000;
	private static final long OFFLINE_REFRESH_INTERVAL = 10 * 1000;
	private static final int RETRY_TIMES = 3;
	private static final String CONFIG_SERVICE_URL_PATH = "/services/config";

	private ScheduledExecutorService refreshConfigServiceAddressService;
	private RestTemplate restTemplate;
	private List<Env> allEnvs;
	private Map<Env, List<ServiceDTO>> cache = new ConcurrentHashMap<>();

	@Autowired
	private HttpMessageConverters httpMessageConverters;
	@Autowired
	private PortalSettings portalSettings;
	@Autowired
	private RestTemplateFactory restTemplateFactory;

	@PostConstruct
	public void init() {
		allEnvs = portalSettings.getAllEnvs();

		// init restTemplate
		restTemplate = restTemplateFactory.getObject();

		refreshConfigServiceAddressService = Executors.newScheduledThreadPool(1,
				ApolloThreadFactory.create("ConfigServiceLocator", true));

		refreshConfigServiceAddressService.schedule(new RefreshConfigServerAddressTask(), 100, TimeUnit.MILLISECONDS);
	}

	public List<ServiceDTO> getServiceList(Env env) {
		List<ServiceDTO> services = cache.get(env);
		if (CollectionUtils.isEmpty(services)) {
			return Collections.emptyList();
		}

		List<ServiceDTO> randomConfigServices = Lists.newArrayList(services);
		Collections.shuffle(randomConfigServices);
		return randomConfigServices;
	}

	private class RefreshConfigServerAddressTask implements Runnable {

		@Override
		public void run() {
			boolean refreshSuccess = true;
			// refresh fail if get any env address fail
			for (Env env : allEnvs) {
				boolean currentEnvRefreshResult = refreshServerAddressCache(env);
				refreshSuccess = refreshSuccess && currentEnvRefreshResult;
			}

			if (refreshSuccess) {
				refreshConfigServiceAddressService.schedule(new RefreshConfigServerAddressTask(),
						NORMAL_REFRESH_INTERVAL, TimeUnit.MILLISECONDS);
			} else {
				refreshConfigServiceAddressService.schedule(new RefreshConfigServerAddressTask(),
						OFFLINE_REFRESH_INTERVAL, TimeUnit.MILLISECONDS);
			}
		}
	}

	private boolean refreshServerAddressCache(Env env) {
		for (int i = 0; i < RETRY_TIMES; i++) {
			try {
				ServiceDTO[] services = getConfigServerAddress(env);
				if (services == null || services.length == 0) {
					continue;
				}
				cache.put(env, Arrays.asList(services));
				return true;
			} catch (Throwable e) {
				logger.error(String.format(
						"Get config server address from meta server failed. env: %s, meta server address:%s", env,
						MetaDomainConsts.getDomain(env)), e);
				Tracer.logError(String.format(
						"Get config server address from meta server failed. env: %s, meta server address:%s", env,
						MetaDomainConsts.getDomain(env)), e);
			}
		}
		return false;
	}

	private ServiceDTO[] getConfigServerAddress(Env env) {
		String domainName = MetaDomainConsts.getDomain(env);
		String url = domainName + CONFIG_SERVICE_URL_PATH;
		return restTemplate.getForObject(url, ServiceDTO[].class);
	}

}

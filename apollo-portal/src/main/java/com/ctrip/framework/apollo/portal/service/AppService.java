package com.ctrip.framework.apollo.portal.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ctrip.framework.apollo.common.dto.AppDTO;
import com.ctrip.framework.apollo.common.dto.ClusterDTO;
import com.ctrip.framework.apollo.common.dto.NamespaceDTO;
import com.ctrip.framework.apollo.common.entity.App;
import com.ctrip.framework.apollo.common.entity.AppNamespace;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.utils.BeanUtils;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.portal.api.AdminServiceAPI;
import com.ctrip.framework.apollo.portal.component.PortalSettings;
import com.ctrip.framework.apollo.portal.constant.PermissionType;
import com.ctrip.framework.apollo.portal.constant.TracerEventType;
import com.ctrip.framework.apollo.portal.entity.bo.UserInfo;
import com.ctrip.framework.apollo.portal.entity.vo.EnvClusterInfo;
import com.ctrip.framework.apollo.portal.repository.AppRepository;
import com.ctrip.framework.apollo.portal.spi.UserInfoHolder;
import com.ctrip.framework.apollo.portal.spi.UserService;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.google.common.collect.Lists;

@Service
public class AppService {

  @Autowired
  private UserInfoHolder userInfoHolder;
  @Autowired
  private AdminServiceAPI.AppAPI appAPI;
  @Autowired
  private AdminServiceAPI.NamespaceAPI namespaceAPI;
  @Autowired
  private AdminServiceAPI.ClusterAPI clusterAPI;
  @Autowired
  private AppRepository appRepository;
  @Autowired
  private ClusterService clusterService;
  @Autowired
  private AppNamespaceService appNamespaceService;
  @Autowired
  private RoleInitializationService roleInitializationService;
  @Autowired
  private UserService userService;
  @Autowired
  private RolePermissionService rolePermissionService;
  @Autowired
  private PortalSettings portalSettings;
  
	private boolean isSuperAdmin() {
		return rolePermissionService.isSuperAdmin(userInfoHolder.getUser().getUserId());
	}

	private boolean isOwner(String ownerName) {
		return ownerName.equalsIgnoreCase(userInfoHolder.getUser().getUserId());
	}

	private boolean hasAssignRolePermission(String appId) {
		return rolePermissionService.userHasPermission(userInfoHolder.getUser().getUserId(),
				PermissionType.ASSIGN_ROLE, appId);
	}

	public List<App> findAll() {
		Iterable<App> apps = appRepository.findAll();
		if (apps == null) {
			return Collections.emptyList();
		}
		return Lists.newArrayList((apps));
	}

	public List<App> findAllByUserId() {
		Iterable<App> apps = appRepository.findAll();
		if (apps == null) {
			return Collections.emptyList();
		}

		ArrayList<App> newList = Lists.newArrayList();
		for (App app : apps) {
			String ownerName = app.getOwnerName();
			if (isOwner(ownerName) || isSuperAdmin() || hasAssignRolePermission(app.getAppId())) {
				newList.add(app);
			}
		}

		return newList;
	}

  public List<App> findByAppIds(Set<String> appIds) {
    return appRepository.findByAppIdIn(appIds);
  }

  public List<App> findByOwnerName(String ownerName, Pageable page) {
    return appRepository.findByOwnerName(ownerName, page);
  }

  public App load(String appId) {
    return appRepository.findByAppId(appId);
  }

  public AppDTO load(Env env, String appId) {
    return appAPI.loadApp(env, appId);
  }

  public void createAppInRemote(Env env, App app) {
    String username = userInfoHolder.getUser().getUserId();
    app.setDataChangeCreatedBy(username);
    app.setDataChangeLastModifiedBy(username);

    AppDTO appDTO = BeanUtils.transfrom(AppDTO.class, app);
    appAPI.createApp(env, appDTO);
  }
  
  @Transactional
	public void deleteApp(App app) {
		final String appId = app.getAppId();
		final String operator = userInfoHolder.getUser().getUserId();
		List<AppNamespace> appNamespaces = appNamespaceService.findByAppId(appId);
		final List<Env> allEnvs = portalSettings.getAllEnvs();
		for (Env envEnum : allEnvs) {
			for (AppNamespace appNamespace : appNamespaces) {
				if (appNamespace.isPublic()) {
					int count = 0;
					try {
						count = namespaceAPI.countPublicAppNamespaceAssociatedNamespaces(envEnum,
								appNamespace.getName());
					} catch (Exception e) {
					}

					if (count > 0) {
						throw new BadRequestException(
								String.format(
										"Can not delete this app because the namespace have associated namespace. namespace = %s",
										appNamespace.getName()));
					}
				}
			}
		}

		Map<Env, List<ClusterDTO>> envClusterMap = new HashMap<Env, List<ClusterDTO>>();
		for (Env envEnum : allEnvs) {
			List<ClusterDTO> clusters = clusterAPI.findClustersByApp(appId, envEnum);
			envClusterMap.put(envEnum, clusters);
		}

		for (AppNamespace appNamespace : appNamespaces) {
			// 删除portal的appnamespace
			appNamespaceService.deleteById(appNamespace.getId());
			for (Env envEnum : allEnvs) {
				try {
					// 删除config库appnamespace中的数据
					namespaceAPI.deleteAppNamespace(envEnum, appId, appNamespace.getName(), operator);
				} catch (Exception e) {
				}
			}
		}

		for (Env envEnum : allEnvs) {
			List<ClusterDTO> clusters = envClusterMap.get(envEnum);
			for (ClusterDTO clusterDTO : clusters) {
				List<NamespaceDTO> namespaces = namespaceAPI.findNamespaceByCluster(appId, envEnum,
						clusterDTO.getName());
				for (NamespaceDTO namespaceDTO : namespaces) {
					try {
						// 删除config库中对应的所有namespace
						namespaceAPI.deleteNamespace(envEnum, appId, clusterDTO.getName(),
								namespaceDTO.getNamespaceName(), operator);
					} catch (Exception e) {
					}
				}
			}
		}

		// 删除portal中对应的app
		appRepository.delete(app.getId());

		for (Env envEnum : allEnvs) {
			List<ClusterDTO> clusters = envClusterMap.get(envEnum);
			for (ClusterDTO clusterDTO : clusters) {
				// 删除config库中的cluster信息
				clusterAPI.delete(envEnum, appId, clusterDTO.getName(), operator);
			}

			// 删除config库中app
			appAPI.deleteApp(envEnum, appId, operator);
		}
	}

  @Transactional
  public App createAppInLocal(App app) {
    String appId = app.getAppId();
    App managedApp = appRepository.findByAppId(appId);

    if (managedApp != null) {
      throw new BadRequestException(String.format("App already exists. AppId = %s", appId));
    }

    UserInfo owner = userService.findByUserId(app.getOwnerName());
    if (owner == null) {
      throw new BadRequestException("Application's owner not exist.");
    }
    app.setOwnerEmail(owner.getEmail());

    String operator = userInfoHolder.getUser().getUserId();
    app.setDataChangeCreatedBy(operator);
    app.setDataChangeLastModifiedBy(operator);

    App createdApp = appRepository.save(app);

    appNamespaceService.createDefaultAppNamespace(appId);
    roleInitializationService.initAppRoles(createdApp);

    Tracer.logEvent(TracerEventType.CREATE_APP, appId);

    return createdApp;
  }

  @Transactional
  public App updateAppInLocal(App app) {
    String appId = app.getAppId();

    App managedApp = appRepository.findByAppId(appId);
    if (managedApp == null) {
      throw new BadRequestException(String.format("App not exists. AppId = %s", appId));
    }

    managedApp.setName(app.getName());
    managedApp.setOrgId(app.getOrgId());
    managedApp.setOrgName(app.getOrgName());

    String ownerName = app.getOwnerName();
    UserInfo owner = userService.findByUserId(ownerName);
    if (owner == null) {
      throw new BadRequestException(String.format("App's owner not exists. owner = %s", ownerName));
    }
    managedApp.setOwnerName(owner.getUserId());
    managedApp.setOwnerEmail(owner.getEmail());

    String operator = userInfoHolder.getUser().getUserId();
    managedApp.setDataChangeLastModifiedBy(operator);

    return appRepository.save(managedApp);
  }

  public EnvClusterInfo createEnvNavNode(Env env, String appId) {
    EnvClusterInfo node = new EnvClusterInfo(env);
    node.setClusters(clusterService.findClusters(env, appId));
    return node;
  }

}

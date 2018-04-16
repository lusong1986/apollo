package com.ctrip.framework.apollo.portal.spi;

import java.util.List;

import com.ctrip.framework.apollo.portal.entity.bo.UserInfo;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
public interface UserService {
  List<UserInfo> searchUsers(String keyword, int offset, int limit);

  UserInfo findByUserId(String userId);

  List<UserInfo> findByUserIds(List<String> userIds);

}

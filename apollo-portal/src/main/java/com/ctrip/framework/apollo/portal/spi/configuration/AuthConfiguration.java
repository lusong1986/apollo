package com.ctrip.framework.apollo.portal.spi.configuration;

import org.apache.tomcat.jdbc.pool.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import com.ctrip.framework.apollo.common.condition.ConditionalOnMissingProfile;
import com.ctrip.framework.apollo.portal.spi.LogoutHandler;
import com.ctrip.framework.apollo.portal.spi.SsoHeartbeatHandler;
import com.ctrip.framework.apollo.portal.spi.UserInfoHolder;
import com.ctrip.framework.apollo.portal.spi.UserService;
import com.ctrip.framework.apollo.portal.spi.defaultimpl.DefaultLogoutHandler;
import com.ctrip.framework.apollo.portal.spi.defaultimpl.DefaultSsoHeartbeatHandler;
import com.ctrip.framework.apollo.portal.spi.defaultimpl.DefaultUserInfoHolder;
import com.ctrip.framework.apollo.portal.spi.defaultimpl.DefaultUserService;
import com.ctrip.framework.apollo.portal.spi.springsecurity.SpringSecurityUserInfoHolder;
import com.ctrip.framework.apollo.portal.spi.springsecurity.SpringSecurityUserService;

@Configuration
public class AuthConfiguration {

	/**
	 * spring.profiles.active = auth
	 */
	@Configuration
	@Profile("auth")
	static class SpringSecurityAuthAutoConfiguration {

		@Bean
		@ConditionalOnMissingBean(SsoHeartbeatHandler.class)
		public SsoHeartbeatHandler defaultSsoHeartbeatHandler() {
			return new DefaultSsoHeartbeatHandler();
		}

		@Bean
		@ConditionalOnMissingBean(UserInfoHolder.class)
		public UserInfoHolder springSecurityUserInfoHolder() {
			return new SpringSecurityUserInfoHolder();
		}

		@Bean
		@ConditionalOnMissingBean(LogoutHandler.class)
		public LogoutHandler logoutHandler() {
			return new DefaultLogoutHandler();
		}

		@Bean
		public JdbcUserDetailsManager jdbcUserDetailsManager(AuthenticationManagerBuilder auth, DataSource datasource)
				throws Exception {
			JdbcUserDetailsManager jdbcUserDetailsManager = auth.jdbcAuthentication()
					.passwordEncoder(new BCryptPasswordEncoder()).dataSource(datasource)
					.usersByUsernameQuery("select Username,Password,Enabled from `Users` where Username = ?")
					.authoritiesByUsernameQuery("select Username,Authority from `Authorities` where Username = ?")
					.getUserDetailsService();

			jdbcUserDetailsManager.setUserExistsSql("select Username from `Users` where Username = ?");
			jdbcUserDetailsManager.setCreateUserSql("insert into `Users` (Username, Password, Enabled) values (?,?,?)");
			jdbcUserDetailsManager.setUpdateUserSql("update `Users` set Password = ?, Enabled = ? where Username = ?");
			jdbcUserDetailsManager.setDeleteUserSql("delete from `Users` where Username = ?");
			jdbcUserDetailsManager
					.setCreateAuthoritySql("insert into `Authorities` (Username, Authority) values (?,?)");
			jdbcUserDetailsManager.setDeleteUserAuthoritiesSql("delete from `Authorities` where Username = ?");
			jdbcUserDetailsManager.setChangePasswordSql("update `Users` set Password = ? where Username = ?");

			return jdbcUserDetailsManager;
		}

		@Bean
		@ConditionalOnMissingBean(UserService.class)
		public UserService springSecurityUserService() {
			return new SpringSecurityUserService();
		}

	}

	@Order(99)
	@Profile("auth")
	@Configuration
	@EnableWebSecurity
	@EnableGlobalMethodSecurity(prePostEnabled = true)
	static class SpringSecurityConfigurer extends WebSecurityConfigurerAdapter {

		public static final String USER_ROLE = "user";

		@Override
		protected void configure(HttpSecurity http) throws Exception {
			http.csrf().disable();
			http.headers().frameOptions().sameOrigin();
			http.authorizeRequests()
					.antMatchers("/openapi/**", "/vendor/**", "/styles/**", "/scripts/**", "/views/**", "/img/**")
					.permitAll().antMatchers("/openapi/*").permitAll().antMatchers("/**").hasAnyRole(USER_ROLE);

			http.formLogin().loginPage("/signin").permitAll().failureUrl("/signin?#/error").and().httpBasic();
			http.logout().invalidateHttpSession(true).clearAuthentication(true).logoutSuccessUrl("/signin?#/logout");
			http.exceptionHandling().authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/signin"));
		}

	}

	/**
	 * default profile
	 */
	@Configuration
	@ConditionalOnMissingProfile({ "ctrip", "auth" })
	static class DefaultAuthAutoConfiguration {

		@Bean
		@ConditionalOnMissingBean(SsoHeartbeatHandler.class)
		public SsoHeartbeatHandler defaultSsoHeartbeatHandler() {
			return new DefaultSsoHeartbeatHandler();
		}

		@Bean
		@ConditionalOnMissingBean(UserInfoHolder.class)
		public DefaultUserInfoHolder defaultUserInfoHolder() {
			return new DefaultUserInfoHolder();
		}

		@Bean
		@ConditionalOnMissingBean(LogoutHandler.class)
		public DefaultLogoutHandler logoutHandler() {
			return new DefaultLogoutHandler();
		}

		@Bean
		@ConditionalOnMissingBean(UserService.class)
		public UserService defaultUserService() {
			return new DefaultUserService();
		}
	}

	@ConditionalOnMissingProfile("auth")
	@Configuration
	@EnableWebSecurity
	@EnableGlobalMethodSecurity(prePostEnabled = true)
	static class DefaultWebSecurityConfig extends WebSecurityConfigurerAdapter {

		@Override
		protected void configure(HttpSecurity http) throws Exception {
			http.csrf().disable();
			http.headers().frameOptions().sameOrigin();
		}
	}
}

package com.macro.mall.security.component;

import cn.hutool.core.collection.CollUtil;
import org.springframework.security.access.AccessDecisionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Iterator;

/**
 * 动态权限决策管理器，用于判断用户是否有访问权限
 * Created by macro on 2020/2/7.
 */
public class DynamicAccessDecisionManager implements AccessDecisionManager {

    @Override
    public void decide(Authentication authentication, Object object,
                       Collection<ConfigAttribute> configAttributes) throws AccessDeniedException, InsufficientAuthenticationException {
        // 当接口未被配置资源时直接放行
        if (CollUtil.isEmpty(configAttributes)) {
            return;
        }
        //创建资源迭代器
        Iterator<ConfigAttribute> iterator = configAttributes.iterator();
        while (iterator.hasNext()) {
            ConfigAttribute configAttribute = iterator.next();
            //将访问所需资源或用户拥有资源进行比对
            /**
             * ConfigAttrbute：用于描述访问控制规则的配置属性。它是一个接口，通常通过实现类来表示具体的访问控制规则。
             * 通过调用 getAttribute() 方法，可以获取配置属性中定义的与安全相关的值，例如角色名、权限标识等。
             * 这些属性值可以用于授权决策，确定请求是否允许访问特定的资源或执行特定的操作。
             */
            String needAuthority = configAttribute.getAttribute();
            /**
             * Authentication：用于表示应用程序中的身份验证信息。
             * 它包含了身份验证相关的主要方法和属性。
             * 其方法getPrincipal()返回经过身份验证的主体（通常是一个用户对象）、
             * getAuthorities()获取经过身份验证的用户的权限信息、
             * getCredentials()返回用于身份验证的凭据；
             */
            /**
             * GrantedAuthority：代表授予用户的权限。
             * 当一个用户通过身份验证后，他们会被授予一个或多个GrantedAuthority对象，这些对象表示用户被赋予的权限或角色，这些权限可以用于后续的访问控制决策。
             */
            for (GrantedAuthority grantedAuthority : authentication.getAuthorities()) {
                if (needAuthority.trim().equals(grantedAuthority.getAuthority())) {
                    return;
                }
            }
        }
        throw new AccessDeniedException("抱歉，您没有访问权限");
    }

    @Override
    public boolean supports(ConfigAttribute configAttribute) {
        return true;
    }

    @Override
    public boolean supports(Class<?> aClass) {
        return true;
    }

}

# Spring Security 动态权限校验流程详解

> 本文档基于 mall 项目 `mall-security` 模块，从宏观设计角度阐述如何通过自定义组件实现数据库驱动的动态权限校验，不涉及具体代码细节，重在梳理职责分工与调用链路。

## 一、核心设计目标

- 实现**运行时动态加载**权限规则，管理员修改角色权限后无需重启服务即可生效。
- 权限粒度控制在**接口（URL）级别**，满足常规后台管理系统的需求。

## 二、关键组件职责一览

| 组件                                | 所属层次   | 核心职责                                                     |
| :---------------------------------- | :--------- | :----------------------------------------------------------- |
| **自定义 `UserDetails` 实现类**     | 认证层     | 封装后台用户信息及其拥有的权限列表（格式为 `资源ID:资源名称`） |
| **`UserDetailsService` 实现**       | 认证层     | 登录时从数据库加载用户与权限，构造 `UserDetails` 对象        |
| **`DynamicSecurityFilter`**         | 授权入口   | 自定义过滤器，插入 Spring Security 过滤器链，决定哪些请求需要被拦截校验 |
| **`DynamicSecurityMetadataSource`** | 授权元数据 | 维护 URL 与所需权限的映射关系，启动时从数据库加载，支持热刷新 |
| **`DynamicAccessDecisionManager`**  | 授权决策   | 比对用户实际权限与当前 URL 所需权限，做出“放行”或“拒绝”的最终裁决 |

## 三、整体工作流程

### 1. 登录阶段 —— 权限的加载与封装

- 用户提交登录凭证。
- Spring Security 调用 `UserDetailsService.loadUserByUsername()` 查询用户信息。
- 同时查询该用户被授予的所有资源权限，转换为 `GrantedAuthority` 集合（标识格式：`资源ID:资源名称`）。
- 将用户信息和权限列表封装进自定义的 `UserDetails` 实现类。
- 认证成功后，Spring Security 自动构造 `Authentication` 对象（内含 `UserDetails` 及权限），并存入 `SecurityContextHolder`，供整个会话期间使用。

### 2. 请求到达 —— 过滤器链的触发

- 用户发起 HTTP 请求。
- 请求经过 Spring Security 标准过滤器链。
- 在 `FilterSecurityInterceptor` 之前，自定义的 `DynamicSecurityFilter` 被触发。

### 3. `DynamicSecurityFilter` —— 拦截与分发

`DynamicSecurityFilter` 继承 `AbstractSecurityInterceptor`（获得安全拦截的标准流程）并实现 `Filter` 接口。其 `doFilter` 方法执行以下判断：

- **封装请求**：将 `ServletRequest` 包装为 `FilterInvocation` 对象，供后续安全组件使用。
- **直接放行场景**：
  - 请求方法为 `OPTIONS`（跨域预检）。
  - 请求 URL 匹配白名单配置（如登录、注册、静态资源等）。
- **需校验场景**：
  - 调用父类 `beforeInvocation(fi)` 方法，正式进入授权决策流程。
  - 若授权通过，则继续执行 `filterChain.doFilter()` 将请求向后传递。
  - 若授权失败，`beforeInvocation` 内部会抛出异常，请求被中断。

### 4. `DynamicSecurityMetadataSource` —— 提供所需权限

当 `beforeInvocation` 被调用时，Spring Security 内部会先请求 `SecurityMetadataSource` 获取当前 URL **需要什么权限**。

- **数据来源**：启动时通过 `DynamicSecurityService` 从数据库加载所有资源（URL 路径）与权限标识的映射，存入内存 `ConcurrentHashMap`。
- **匹配逻辑**：使用 Ant 风格的路径匹配器，根据请求 URL 在 Map 中查找对应的权限标识（格式：`资源ID:资源名称`）。
- **返回结果**：匹配到的权限标识会被封装为 `ConfigAttribute` 集合，传递给下一步的决策管理器。

**热刷新支持**：提供清空 Map 的方法，清空后下一次请求会触发重新加载，从而实现权限规则不重启生效。

### 5. `DynamicAccessDecisionManager` —— 执行权限比对

决策管理器接收三个核心参数：

- `authentication`：当前登录用户的认证信息（包含其拥有的权限列表）。
- `object`：当前请求的安全对象。
- `configAttributes`：上一步返回的所需权限集合。

**决策逻辑**：

- 若 `configAttributes` 为空（即该 URL 未配置任何权限），直接放行。
- 遍历所需权限，逐一与用户 `authentication` 中的权限集合进行比对。
- 采用 **“满足任意一个即通过”** 的策略：只要用户拥有所需权限列表中的任意一项，决策方法立即返回，表示授权成功。
- 若遍历结束仍无匹配项，则抛出 `AccessDeniedException`，请求被拒绝。

### 6. 授权通过后的流程

- `decide` 方法正常返回，`beforeInvocation` 顺利结束。
- `DynamicSecurityFilter` 收到成功信号，继续调用 `filterChain.doFilter()`。
- 请求沿着过滤器链继续前进，最终到达目标 Controller 处理业务逻辑。

## 四、关键设计理解

### 4.1 权限标识的一致性约定

整个动态权限校验的基石是 **权限标识格式的统一**。在 `UserDetailsService` 中赋予用户的权限标识，必须与 `MetadataSource` 中配置的 URL 所需权限标识**完全一致**。本项目采用 `资源ID:资源名称` 的格式，既保证了可读性，也方便数据库关联。

### 4.2 多权限规则的“或”策略

当前决策管理器采用“或”逻辑，适用于“只要具备某个角色或权限即可访问某资源”的场景。若业务要求用户**同时具备多个权限**才能访问，需要修改 `decide` 方法中的比对逻辑，改为全量校验。

### 4.3 热刷新的实现思路

通过 `ConcurrentHashMap` 存储映射，并暴露清空方法。当管理员修改了后台权限配置后，调用清空接口，下一次请求到来时发现 Map 为空，便会自动重新从数据库加载最新规则，全程无需重启应用。这是一种典型的 **懒加载 + 缓存失效** 模式。

### 4.4 过滤器链的插入位置

自定义的 `DynamicSecurityFilter` 必须位于 Spring Security 核心过滤器 `FilterSecurityInterceptor` **之前**。因为 `FilterSecurityInterceptor` 是标准授权终点，我们的动态过滤器需要提前接管授权判断，否则默认的注解式授权机制会先行生效，导致动态规则失效。

## 五、总结

mall 项目通过以下三个自定义组件的协作，实现了灵活、可扩展的动态权限控制：

- **`DynamicSecurityMetadataSource`** 回答了“这个 URL 需要什么权限？”
- **`DynamicAccessDecisionManager`** 回答了“当前用户有没有这个权限？”
- **`DynamicSecurityFilter`** 作为入口，决定了“哪些请求需要走这套动态流程？”

该设计充分复用了 Spring Security 的扩展点，将权限规则的控制权从代码转移到了数据库，是后台管理系统权限设计的经典范式。

## 六、权限模块全流程理解

我清晰的理解了整个动态权限校验的逻辑，我们需要实s现UserDetails接口，自定义SpringSecurity需要的用户信息封装类，封装后台用户以及权限列表（在这里是“资源id:资源名”），在登录时将userDetails和权限封装为authentication对象并交给SpringSecurity管理，我们在权限过滤器链FilterSecurityInterceptor前加上自定的动态权限过滤链DynamicSecurityFilter，需要继承AbstractSecurityInterceptor活动基础的安全拦截功能，并且实现filter接口。其中我们需要关注的是dofilter方法，封装请求FilterInvocation对象，放行options和白名单请求，执行父类beforeInovacation方法，该方法会调用SpringSecurityDatabaseMetadataSource中的getAttributes方法用于加载权限，我们使用ConcurrentHashMap做出api接口与权限的映射（资源id:资源名称），在方法启动时加载到map中，支持热刷新提供了清楚map接口。通过获取url中的api资源路径映射到权限，返回对应权限列表，然后在AccessDecisionManager中的decide方法中进行与用户所持有的权限进行比对，没权限抛异常，有权限返回，放行请求并沿着执行链继续执行。       基于以上我的理解重新给出.md总结文档，不必给出详细的代码

---

*注：本文档为个人对 mall 项目源码的学习总结，旨在梳理设计思想与流程，核心逻辑版权归原项目作者 macrozheng。*
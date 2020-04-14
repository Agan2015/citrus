package com.github.yiuman.citrus.system.rest;

import com.github.yiuman.citrus.security.authorize.Authorize;
import com.github.yiuman.citrus.support.crud.rest.BaseCrudController;
import com.github.yiuman.citrus.support.crud.service.CrudService;
import com.github.yiuman.citrus.system.dto.UserDto;
import com.github.yiuman.citrus.system.dto.UserQuery;
import com.github.yiuman.citrus.system.hook.HasLoginHook;
import com.github.yiuman.citrus.system.service.UserService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户控制层
 *
 * @author yiuman
 * @date 2020/3/30
 */
@RestController
@RequestMapping("/rest/users")
@Authorize(HasLoginHook.class)
public class UserController extends BaseCrudController<UserDto, Long> {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
        setParamClass(UserQuery.class);
    }

    @Override
    protected CrudService<UserDto, Long> getService() {
        return userService;
    }
}
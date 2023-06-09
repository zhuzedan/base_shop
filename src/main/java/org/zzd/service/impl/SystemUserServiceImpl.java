package org.zzd.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.zzd.constant.SecurityConstants;
import org.zzd.dto.user.*;
import org.zzd.entity.SystemUser;
import org.zzd.exception.ResponseException;
import org.zzd.mapper.SystemUserMapper;
import org.zzd.pojo.SecuritySystemUser;
import org.zzd.result.ResponseResult;
import org.zzd.result.ResultCodeEnum;
import org.zzd.service.SystemUserService;
import org.zzd.utils.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @apiNote 用户表(SystemUser)表服务实现类
 * @author zzd
 * @since 2023-03-02 13:53:39
 */
@Service("systemUserService")
public class SystemUserServiceImpl extends ServiceImpl<SystemUserMapper, SystemUser> implements SystemUserService {
    @Autowired
    private SystemUserMapper systemUserMapper;
    @Autowired
    AuthenticationManager authenticationManager;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Resource
    private JwtTokenUtil jwtTokenUtil;

    private final Logger logger = LoggerFactory.getLogger(SystemUserServiceImpl.class);

    @Override
    public ResponseResult login(LoginDto loginDto) throws ResponseException {
        SystemUser login = doLogin(loginDto.getUsername(), loginDto.getPassword());
        SecuritySystemUser user = new SecuritySystemUser(login);
        String token = jwtTokenUtil.generateToken(user);
        LoginRespDto loginRespDto = new LoginRespDto(SecurityConstants.TOKEN_PREFIX, token, jwtTokenUtil.getExpiredDateFromToken(token).getTime());
        return ResponseResult.success("登录成功", loginRespDto);
    }

    public SystemUser doLogin(String username, String password) {

        SystemUser systemUser;
        UserDetails userDetails;
        ThreadLocalUtil.setUsername(username);
        try {
            userDetails = loadUserByUsername(username);
            systemUser = ((SecuritySystemUser) userDetails).getSystemUser();
        } catch (Exception e) {
            throw new ResponseException(ResultCodeEnum.LOGIN_ERROR);
        }
        if (!passwordEncoder.matches(password, systemUser.getPassword())) {
            throw new ResponseException(ResultCodeEnum.PASSWORD_ERROR);
        }
        if (!userDetails.isEnabled()) {
            throw new ResponseException(ResultCodeEnum.ACCOUNT_STOP);
        }

        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        return systemUser;
    }

    @Override
    public ResponseResult loginCaptcha(LoginCaptchaDto loginCaptchaDto, HttpServletRequest request) {
        SystemUser login = doCaptchaLogin(loginCaptchaDto, request);
        SecuritySystemUser user = new SecuritySystemUser(login);
        String token = jwtTokenUtil.generateToken(user);
        LoginRespDto loginRespDto = new LoginRespDto(SecurityConstants.TOKEN_PREFIX, token, jwtTokenUtil.getExpiredDateFromToken(token).getTime());
        return ResponseResult.success("登录成功", loginRespDto);
    }

    public SystemUser doCaptchaLogin(LoginCaptchaDto loginCaptchaDto, HttpServletRequest request) {
        SystemUser systemUser;
        UserDetails userDetails;
        String captcha = (String) request.getSession().getAttribute("captcha");
        logger.info("调用验证码接口后存在session中的：" + captcha);
        logger.info("前端实际输入的：" + loginCaptchaDto.getCaptcha());
        if (null == captcha || !captcha.equals(loginCaptchaDto.getCaptcha())) {
            throw new ResponseException("验证码不正确");
        }
        try {
            userDetails = loadUserByUsername(loginCaptchaDto.getUsername());
            systemUser = ((SecuritySystemUser) userDetails).getSystemUser();
        } catch (Exception e) {
            throw new ResponseException(ResultCodeEnum.LOGIN_ERROR);
        }
        if (!passwordEncoder.matches(loginCaptchaDto.getPassword(), systemUser.getPassword())) {
            throw new ResponseException("密码错误");
        }
        if (!userDetails.isEnabled()) {
            throw new ResponseException("账号已停用");
        }

        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        return systemUser;
    }

    /**
     * @apiNote 获取用户信息
     * @return org.zzd.result.ResponseResult
     */
    @Override
    public ResponseResult getInfo() {
        SecuritySystemUser systemSecurityUser = SecurityUtils.getCurrentSecuritySystemUser();
        SystemUser systemUser = systemSecurityUser.getSystemUser();
        UserInfoDto userInfoDto = new UserInfoDto(systemUser.getId(), systemUser.getNickname(), systemUser.getAvatar(), systemUser.getDescription(), null);
        return ResponseResult.success(userInfoDto);
    }

    @Override
    public ResponseResult<PageHelper<SystemUser>> queryPage(UserInfoPageParam params) {
        LambdaQueryWrapper<SystemUser> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        // 用户名模糊查询
        if (!StringUtils.isBlank(params.getUsername())) {
            lambdaQueryWrapper.like(SystemUser::getUsername, params.getUsername());
        }
        Page<SystemUser> page = new Page<>(params.getPageNum(), params.getPageSize());
        IPage<SystemUser> iPage = systemUserMapper.selectPage(page, lambdaQueryWrapper);
        return ResponseResult.success(PageHelper.restPage(iPage));
    }

    /**
     * @apiNote 新建用户
     * @param createUserDto: 新建用户的对象
     * @return org.zzd.result.ResponseResult
     */
    @Override
    public ResponseResult insertSystemUser(CreateUserDto createUserDto) {
        if (StringUtils.isBlank(createUserDto.getUsername())) {
            throw new ResponseException("登录名不能为空");
        }
        Long count = systemUserMapper.selectCount(new QueryWrapper<SystemUser>().eq("username", createUserDto.getUsername()));
        if (count > 0) {
            throw new ResponseException("用户已存在");
        }
        SystemUser systemUser = SystemUser.insertUserConvert(createUserDto);
        if (!StringUtils.isBlank(systemUser.getPassword())) {
            systemUser.setPassword(passwordEncoder.encode(systemUser.getPassword()));
        }
        //创建人
        systemUser.setCreateBy(SecurityUtils.getCurrentSystemUser().getUsername());
        systemUserMapper.insert(systemUser);
        return ResponseResult.success();
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        SystemUser systemUser = systemUserMapper.selectOne(new QueryWrapper<SystemUser>().eq("username", username));
        boolean isAdmin = username.contains("admin");
        if (isAdmin) {
            //授权，默认只授予角色：ADMIN和USER
            String[] roles = {"ROLE_ADMIN","ROLE_USER"};
            List<GrantedAuthority> grantedAuthorities = AuthorityUtils.createAuthorityList(roles);
            return new SecuritySystemUser(systemUser, grantedAuthorities);
        } else {
            String[] roles = {"ROLE_USER"};
            List<GrantedAuthority> grantedAuthorities = AuthorityUtils.createAuthorityList(roles);
            return new SecuritySystemUser(systemUser, grantedAuthorities);
        }
    }
}
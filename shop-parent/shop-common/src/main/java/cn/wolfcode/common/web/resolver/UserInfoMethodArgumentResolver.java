package cn.wolfcode.common.web.resolver;

import cn.wolfcode.common.domain.UserInfo;
import cn.wolfcode.redis.CommonRedisKey;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class UserInfoMethodArgumentResolver implements HandlerMethodArgumentResolver {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean supportsParameter(MethodParameter methodParameter) {
        //判断是否需要解析参数
        //添加注解是因为不是所有的UserInfo类型都需要从redis里面拿数据，因为存在前端修改用户信息的情况，此时需要从前端拿信息
        return methodParameter.hasParameterAnnotation(RequestUserInfo.class)
                && methodParameter.getParameterType()== UserInfo.class;
    }

    @Override
    public Object resolveArgument(MethodParameter methodParameter, ModelAndViewContainer modelAndViewContainer, NativeWebRequest nativeWebRequest, WebDataBinderFactory webDataBinderFactory) throws Exception {
        //从请求头中获取token
        String token=nativeWebRequest.getHeader("token");
        return JSON.parseObject(stringRedisTemplate.opsForValue().get(CommonRedisKey.USER_TOKEN.getRealKey(token)), UserInfo.class);
    }
}

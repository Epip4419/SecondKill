package cn.wolfcode.web.controller;

import cn.wolfcode.common.constants.CommonConstants;
import cn.wolfcode.common.domain.UserInfo;
import cn.wolfcode.common.exception.BusinessException;
import cn.wolfcode.common.web.Result;
import cn.wolfcode.common.web.anno.RequireLogin;
import cn.wolfcode.common.web.resolver.RequestUserInfo;
import cn.wolfcode.domain.OrderInfo;
import cn.wolfcode.domain.SeckillProductVo;
import cn.wolfcode.redis.CommonRedisKey;
import cn.wolfcode.service.IOrderInfoService;
import cn.wolfcode.service.ISeckillProductService;
import cn.wolfcode.util.AssertUtils;
import cn.wolfcode.util.DateUtil;
import cn.wolfcode.web.msg.SeckillCodeMsg;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Calendar;
import java.util.Date;


@RestController
@RequestMapping("/order")
@Slf4j
public class OrderInfoController {
    @Autowired
    private ISeckillProductService seckillProductService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    //    @Autowired
//    private RocketMQTemplate rocketMQTemplate;
    @Autowired
    private IOrderInfoService orderInfoService;

    /**
     * 优化前：
     *  测试数据：500 个用户，100 线程，执行 50 次
     *  测试情况：330 QPS
     */
    @RequireLogin
    @RequestMapping("/doSeckill")
    public Result<String> doSeckill(Integer time, Long seckillId, @RequestUserInfo UserInfo userInfo) {
        // 1. 基于 token 获取到用户信息(必须登录)

        // 2. 基于场次+秒杀id获取到秒杀商品对象
        SeckillProductVo vo = seckillProductService.selectByIdAndTime(seckillId, time);
//        if (vo == null) {
//            throw new BusinessException(SeckillCodeMsg.REMOTE_DATA_ERROR);
//        }
        AssertUtils.notNUll(vo,"非法操作");
        // 3. 判断时间是否大于开始时间 && 小于 开始时间+2小时
        /*if (!DateUtil.isLegalTime(vo.getStartDate(), time)) {
            throw new BusinessException(SeckillCodeMsg.OUT_OF_SECKILL_TIME_ERROR);
        }*/
        boolean range=this.isBetweenSeckillTime(vo.getTime());
        AssertUtils.isTrue(range,"不在秒杀时间内");
        // 4. 判断用户是否重复下单
        // 基于用户 + 秒杀 id + 场次查询订单, 如果存在订单, 说明用户已经下过单
        OrderInfo orderInfo = orderInfoService.selectByUserIdAndSeckillId(userInfo.getPhone(), seckillId, time);
//        if (orderInfo != null) {
//            throw new BusinessException(SeckillCodeMsg.REPEAT_SECKILL);
//        }
        AssertUtils.isTrue(orderInfo==null,"您已经购买过此商品，不能重复下单");
        // 5. 判断库存是否充足
//        if (vo.getStockCount() <= 0) {
//            throw new BusinessException(SeckillCodeMsg.SECKILL_STOCK_OVER);
//        }
        AssertUtils.isTrue(vo.getStockCount() > 0,"你来晚了，商品已经卖完了");
        // 6. 执行下单操作(减少库存, 创建订单)
        String orderNo = orderInfoService.doSeckill(userInfo, vo);
        return Result.success(orderNo);
    }

    private Boolean isBetweenSeckillTime(Integer time) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, time);
        //开始时间
        Date startDate = calendar.getTime();
        //结束时间
        calendar.add(Calendar.HOUR_OF_DAY,2);
        Date endDate = calendar.getTime();
        //现在的时间
        Long now = System.currentTimeMillis();
        return now >= startDate.getTime() && now <= endDate.getTime();
    }

    private UserInfo getUserByToken(String token) {
        return JSON.parseObject(redisTemplate.opsForValue().get(CommonRedisKey.USER_TOKEN.getRealKey(token)), UserInfo.class);
    }
}

package cn.wolfcode.service.impl;

import cn.wolfcode.common.exception.BusinessException;
import cn.wolfcode.common.web.CodeMsg;
import cn.wolfcode.common.web.Result;
import cn.wolfcode.domain.Product;
import cn.wolfcode.domain.SeckillProduct;
import cn.wolfcode.domain.SeckillProductVo;
import cn.wolfcode.feign.ProductFeignApi;
import cn.wolfcode.mapper.SeckillProductMapper;
import cn.wolfcode.redis.SeckillRedisKey;
import cn.wolfcode.service.ISeckillProductService;
import cn.wolfcode.util.AssertUtils;
import cn.wolfcode.util.IdGenerateUtil;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@CacheConfig(cacheNames = "SeckillProduct")
public class SeckillProductServiceImpl implements ISeckillProductService {
    @Autowired
    private SeckillProductMapper seckillProductMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedisScript<Boolean> redisScript;

    @Autowired
    private ProductFeignApi productFeignApi;

    @Autowired
    private ScheduledExecutorService scheduledExecutorService;
//    @Autowired
//    private RocketMQTemplate rocketMQTemplate;

    @Override
    public List<SeckillProductVo> selectTodayListByTime(Integer time) {
        // 1. 调用秒杀服务接口, 基于今天的时间, 查询今天的所有秒杀商品数据
        List<SeckillProduct> todayList = seckillProductMapper.queryCurrentlySeckillProduct(time);
        if (todayList.size() == 0) {
            return Collections.emptyList();
        }
        // 2. 遍历秒杀商品列表, 得到商品 id 列表
        List<Long> productIdList = todayList.stream() // Stream<SeckillProduct>
                .map(SeckillProduct::getProductId) // SeckillProduct => Long
                .distinct()
                .collect(Collectors.toList());
        // 3. 根据商品 id 列表, 调用商品服务查询接口, 得到商品列表
        Result<List<Product>> result = productFeignApi.selectByIdList(productIdList);
        /**
         * result 可能存在的几种情况:
         *  1. 远程接口正常返回, code == 200, data == 想要的数据
         *  2. 远程接口出现异常, code != 200
         *  3. 接口被熔断降级, data == null
         */
        if (result.hasError() || result.getData() == null) {
            throw new BusinessException(new CodeMsg(result.getCode(), result.getMsg()));
        }

        List<Product> products = result.getData();

        // 4. 遍历秒杀商品列表, 将商品对象与秒杀商品对象聚合到一起
        // List<SeckillProduct> => List<SeckillProductVo>
        List<SeckillProductVo> productVoList = todayList.stream()
                .map(sp -> {
                    SeckillProductVo vo = new SeckillProductVo();
                    BeanUtils.copyProperties(sp, vo);

                    // 遍历远程查询的商品列表，判断是否与当前的秒杀商品关联的商品对象一致
                    // 如果是一致的，将该对象返回并将属性拷贝到 vo 对象中
                    List<Product> list = products.stream().filter(p -> sp.getProductId().equals(p.getId())).collect(Collectors.toList());
                    if (list.size() > 0) {
                        Product product = list.get(0);
                        BeanUtils.copyProperties(product, vo);
                    }
                    vo.setId(sp.getId());

                    return vo;
                }) // Stream<SeckillProductVo>
                .collect(Collectors.toList());

        return productVoList;
    }

    @Override
    public List<SeckillProductVo> selectTodayListByTimeFromRedis(Integer time) {
        String key = SeckillRedisKey.SECKILL_PRODUCT_LIST.join(time + "");
        List<String> stringList = redisTemplate.opsForList().range(key, 0, -1);

        if (stringList == null || stringList.size() == 0) {
            log.warn("[秒杀商品] 查询秒杀商品列表异常, Redis 中没有数据, 从 DB 中查询...");
            return this.selectTodayListByTime(time);
        }

        return stringList.stream().map(json -> JSON.parseObject(json, SeckillProductVo.class)).collect(Collectors.toList());
    }

    @Override
    @Cacheable(key = "'selectByIdAndTime:' + #time + ':' + #seckillId")
    public SeckillProductVo selectByIdAndTime(Long seckillId, Integer time) {
        SeckillProduct seckillProduct = seckillProductMapper.selectByIdAndTime(seckillId, time);
        //远程调用
        Result<List<Product>> result = productFeignApi.selectByIdList(Collections.singletonList(seckillProduct.getProductId()));
        if (result.hasError() || result.getData() == null || result.getData().size() == 0) {
            throw new BusinessException(new CodeMsg(result.getCode(), result.getMsg()));
        }

        Product product = result.getData().get(0);

        SeckillProductVo vo = new SeckillProductVo();
        // 先将商品的属性 copy 到 vo 对象中
        BeanUtils.copyProperties(product, vo);

        // 再将秒杀商品的属性 copy 到 vo 对象中, 并覆盖 id 属性
        BeanUtils.copyProperties(seckillProduct, vo);
        return vo;
    }

    /*
     * 当执行下方的 update 方法时，会自动拼接注解中的 key，将其从 redis 中删除
    @CacheEvict(key = "'selectByIdAndTime:' + #product.time + ':' + #product.id")
    public void update(SeckillProduct product) {
        // 更新操作
    }*/

    @Override
    @CacheEvict(key = "'selectByIdAndTime:'+#id")
    public void decrStockCount(Long id,Integer time) {
        //使用redis实现分布式锁
        //1.锁哪个对象->当前场次的商品对象
        //2.如何实现->①.使用redis的setnx命令（如果已经存在key，则其他线程无法创建key。该方法存在无法原子性地存储锁和设置过期时间的问题
        // ②使用lua脚本，可以设置过期时间，防止了程序挂掉无法清除之前设置的key的问题
        //3.存在哪里->使用redis的String-value结构，存放在redis中
        //4.如果线程拿不到锁，应该执行什么策略？阻塞/自旋/抛出异常
        String key="seckill:product:stockCount"+time+":"+id;
        String threadId="";
        ScheduledFuture<?> future=null;
        try {
            Boolean flag = false;
            int count = 0;
            int timeout = 5;
            do {
                //生成分布式唯一id
                threadId = IdGenerateUtil.get().nextId()+"";
                //①使用setnx命令
                //flag = redisTemplate.opsForValue().setIfAbsent(key,"1");
                //②使用lua脚本

                flag = redisTemplate.execute(redisScript,Collections.singletonList(key),threadId,timeout+"");
                if (flag != null && flag) {
                    break;
                }
                AssertUtils.isTrue(count++ < 5,"系统繁忙，请稍后再试");
                Thread.sleep(10);

            }while (true);
            //加锁成功，创建 WatchDog 监听业务是否执行完成，实现续期操作
            long delayTime=(long) (timeout*0.8);
            String finalThreadId=threadId;
            future= scheduledExecutorService.scheduleAtFixedRate(
                    ()->{
                        //1.查询 Redis中key是否存在，如果存在，就续期
                        String value=redisTemplate.opsForValue().get(key);
                        if (finalThreadId.equals(value)) {
                            //1.1将当前key再次续期
                            redisTemplate.expire(key,delayTime+2,TimeUnit.SECONDS);
                            return;
                        }
                    },
                    delayTime,
                    delayTime,
                    TimeUnit.SECONDS
                    );


            //先查库存
            int vo= seckillProductMapper.getStockCount(id);
            AssertUtils.isTrue(vo>0,"库存不足！");
            //再扣库存
            seckillProductMapper.decrStock(id);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //5.释放锁
            //先根据key获取到value，然后再比较value的值与当前的threadId是否相同
            String value = redisTemplate.opsForValue().get(key);
            if (threadId.equals(value)) {
                redisTemplate.delete(key);
            }
            if (future != null) {
                future.cancel(true);

            }

        }


    }
}

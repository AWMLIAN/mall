package com.macro.mall.portal.service.impl;

import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.PmsBrandMapper;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.model.PmsBrand;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsProductExample;
import com.macro.mall.portal.dao.HomeDao;
import com.macro.mall.portal.service.PmsPortalBrandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 前台品牌管理Service实现类
 * Created by macro on 2020/5/15.
 */
@Slf4j
@Service
public class PmsPortalBrandServiceImpl implements PmsPortalBrandService {
    @Autowired
    private HomeDao homeDao;
    @Autowired
    private PmsBrandMapper brandMapper;
    @Autowired
    private PmsProductMapper productMapper;
    @Autowired
    private RedisService redisService;
    @Value("${redis.database}")
    private String REDIS_DATABASE;
    @Value("page")
    private String REDIS_KEY_PAGE;
    @Value("${redis.key.nullValue}")
    private String REDIS_NULL_VALUE;
    @Value("${redis.expire.break}")
    private Long REDIS_NULL_EXPIRE;
    @Value("${redis.expire.common}")
    private Long REDIS_EXPIRE;
    @Value("${redis.key.brandId}")
    private String REDIS_BRAND;
    @Override
    public List<PmsBrand> recommendList(Integer pageNum, Integer pageSize) {
        int offset = (pageNum - 1) * pageSize;
        return homeDao.getRecommendBrandList(offset, pageSize);
    }

    @Override
    public PmsBrand detail(Long brandId) {
        return brandMapper.selectByPrimaryKey(brandId);
    }

    /**
     * 解决缓存穿透问题
     */
    @Override
    public CommonPage<PmsProduct> productList(Long brandId, Integer pageNum, Integer pageSize) {
        //构建缓存 key
        String cacheKey=REDIS_DATABASE+":"+REDIS_BRAND+":"+brandId+":"+REDIS_KEY_PAGE+":"+pageNum+":"+pageSize;
        //尝试从 redis获取数据
        Object cache = redisService.get(cacheKey);
        if(cache!=null){
            // 缓存命中
            // 如果是空值，返回空列表
            if(REDIS_NULL_VALUE.equals(cache.toString())){
                return CommonPage.restPage(Collections.emptyList());
            }
            return CommonPage.restPage((List<PmsProduct>)cache);
        }
        PageHelper.startPage(pageNum,pageSize);
        PmsProductExample example = new PmsProductExample();
        example.createCriteria().andDeleteStatusEqualTo(0)
                .andPublishStatusEqualTo(1)
                .andBrandIdEqualTo(brandId);
        List<PmsProduct> productList = productMapper.selectByExample(example);
        //根据查询结果写入缓存
        if(productList==null||productList.isEmpty()){
            redisService.set(cacheKey,REDIS_NULL_VALUE,REDIS_NULL_EXPIRE);
        }else{
            redisService.set(cacheKey,productList,REDIS_EXPIRE);
        }
        return CommonPage.restPage(productList);
    }
//    @Override
//    public CommonPage<PmsProduct> productList(Long brandId, Integer pageNum, Integer pageSize) {
//        PageHelper.startPage(pageNum,pageSize);
//        PmsProductExample example = new PmsProductExample();
//        example.createCriteria().andDeleteStatusEqualTo(0)
//                .andPublishStatusEqualTo(1)
//                .andBrandIdEqualTo(brandId);
//        List<PmsProduct> productList = productMapper.selectByExample(example);
//        return CommonPage.restPage(productList);
//    }
}

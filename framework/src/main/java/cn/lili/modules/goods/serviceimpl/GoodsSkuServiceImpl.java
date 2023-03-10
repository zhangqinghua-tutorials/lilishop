package cn.lili.modules.goods.serviceimpl;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.enums.PromotionTypeEnum;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.event.TransactionCommitSendMQEvent;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.utils.SnowFlake;
import cn.lili.modules.goods.entity.dos.Goods;
import cn.lili.modules.goods.entity.dos.GoodsGallery;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.entity.dto.GoodsOperationDTO;
import cn.lili.modules.goods.entity.dto.GoodsSearchParams;
import cn.lili.modules.goods.entity.dto.GoodsSkuDTO;
import cn.lili.modules.goods.entity.dto.GoodsSkuStockDTO;
import cn.lili.modules.goods.entity.enums.GoodsAuthEnum;
import cn.lili.modules.goods.entity.enums.GoodsSalesModeEnum;
import cn.lili.modules.goods.entity.enums.GoodsStatusEnum;
import cn.lili.modules.goods.entity.vos.GoodsSkuSpecVO;
import cn.lili.modules.goods.entity.vos.GoodsSkuVO;
import cn.lili.modules.goods.entity.vos.GoodsVO;
import cn.lili.modules.goods.entity.vos.SpecValueVO;
import cn.lili.modules.goods.mapper.GoodsSkuMapper;
import cn.lili.modules.goods.service.*;
import cn.lili.modules.goods.sku.GoodsSkuBuilder;
import cn.lili.modules.goods.sku.render.SalesModelRender;
import cn.lili.modules.member.entity.dos.FootPrint;
import cn.lili.modules.member.entity.dto.EvaluationQueryParams;
import cn.lili.modules.member.entity.enums.EvaluationGradeEnum;
import cn.lili.modules.member.service.MemberEvaluationService;
import cn.lili.modules.promotion.entity.dos.PromotionGoods;
import cn.lili.modules.promotion.entity.dto.search.PromotionGoodsSearchParams;
import cn.lili.modules.promotion.entity.enums.CouponGetEnum;
import cn.lili.modules.promotion.service.PromotionGoodsService;
import cn.lili.modules.search.entity.dos.EsGoodsIndex;
import cn.lili.modules.search.service.EsGoodsIndexService;
import cn.lili.modules.search.utils.EsIndexUtil;
import cn.lili.mybatis.BaseEntity;
import cn.lili.mybatis.util.PageUtil;
import cn.lili.rocketmq.RocketmqSendCallbackBuilder;
import cn.lili.rocketmq.tags.GoodsTagsEnum;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ??????sku???????????????
 *
 * @author pikachu
 * @since 2020-02-23 15:18:56
 */
@Service
public class GoodsSkuServiceImpl extends ServiceImpl<GoodsSkuMapper, GoodsSku> implements GoodsSkuService {

    /**
     * ??????
     */
    @Autowired
    private Cache cache;
    /**
     * ??????
     */
    @Autowired
    private CategoryService categoryService;
    /**
     * ????????????
     */
    @Autowired
    private GoodsGalleryService goodsGalleryService;
    /**
     * rocketMq
     */
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    /**
     * rocketMq??????
     */
    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;
    /**
     * ????????????
     */
    @Autowired
    private MemberEvaluationService memberEvaluationService;
    /**
     * ??????
     */
    @Autowired
    private GoodsService goodsService;
    /**
     * ????????????
     */
    @Autowired
    private EsGoodsIndexService goodsIndexService;

    @Autowired
    private PromotionGoodsService promotionGoodsService;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private WholesaleService wholesaleService;

    @Autowired
    private List<SalesModelRender> salesModelRenders;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Goods goods, GoodsOperationDTO goodsOperationDTO) {
        // ??????????????????
        if (goodsOperationDTO.getSkuList() == null || goodsOperationDTO.getSkuList().isEmpty()) {
            throw new ServiceException(ResultCode.MUST_HAVE_GOODS_SKU);
        }
        // ??????????????????????????????
        List<GoodsSku> goodsSkus = GoodsSkuBuilder.buildBatch(goods, goodsOperationDTO.getSkuList());
        renderGoodsSkuList(goodsSkus, goodsOperationDTO);

        if (!goodsSkus.isEmpty()) {
            this.saveOrUpdateBatch(goodsSkus);
            this.updateStock(goodsSkus);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Goods goods, GoodsOperationDTO goodsOperationDTO) {
        // ??????????????????
        if (goodsOperationDTO.getSkuList() == null || goodsOperationDTO.getSkuList().isEmpty()) {
            throw new ServiceException(ResultCode.MUST_HAVE_GOODS_SKU);
        }
        List<GoodsSku> skuList;
        //????????????sku??????
        if (Boolean.TRUE.equals(goodsOperationDTO.getRegeneratorSkuFlag())) {
            skuList = GoodsSkuBuilder.buildBatch(goods, goodsOperationDTO.getSkuList());
            renderGoodsSkuList(skuList, goodsOperationDTO);
            List<GoodsSkuVO> goodsListByGoodsId = getGoodsListByGoodsId(goods.getId());
            List<String> oldSkuIds = new ArrayList<>();
            //???????????????
            for (GoodsSkuVO goodsSkuVO : goodsListByGoodsId) {
                oldSkuIds.add(goodsSkuVO.getId());
                cache.remove(GoodsSkuService.getCacheKeys(goodsSkuVO.getId()));
            }

            //??????sku??????
            goodsGalleryService.removeByGoodsId(goods.getId());

            //??????mq??????
            String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.SKU_DELETE.name();
            rocketMQTemplate.asyncSend(destination, JSONUtil.toJsonStr(oldSkuIds), RocketmqSendCallbackBuilder.commonCallback());
        } else {
            skuList = new ArrayList<>();
            for (Map<String, Object> map : goodsOperationDTO.getSkuList()) {
                GoodsSku sku = GoodsSkuBuilder.build(goods, map);
                renderGoodsSku(sku, goodsOperationDTO);
                skuList.add(sku);
                //?????????????????????????????????es????????????
                if (goods.getAuthFlag().equals(GoodsAuthEnum.PASS.name()) && goods.getMarketEnable().equals(GoodsStatusEnum.UPPER.name())) {
                    goodsIndexService.deleteIndexById(sku.getId());
                    this.clearCache(sku.getId());
                }
            }
        }
        if (!skuList.isEmpty()) {
            LambdaQueryWrapper<GoodsSku> unnecessarySkuIdsQuery = new LambdaQueryWrapper<>();
            unnecessarySkuIdsQuery.eq(GoodsSku::getGoodsId, goods.getId());
            unnecessarySkuIdsQuery.notIn(GoodsSku::getId, skuList.stream().map(BaseEntity::getId).collect(Collectors.toList()));
            this.remove(unnecessarySkuIdsQuery);
            this.saveOrUpdateBatch(skuList);
            this.updateStock(skuList);
        }
    }

    /**
     * ????????????sku
     *
     * @param goodsSku sku??????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(GoodsSku goodsSku) {
        this.updateById(goodsSku);
        cache.remove(GoodsSkuService.getCacheKeys(goodsSku.getId()));
        cache.put(GoodsSkuService.getCacheKeys(goodsSku.getId()), goodsSku);
    }


    /**
     * ??????sku??????
     *
     * @param skuId skuID
     */
    @Override
    public void clearCache(String skuId) {
        cache.remove(GoodsSkuService.getCacheKeys(skuId));
    }

    @Override
    public GoodsSku getGoodsSkuByIdFromCache(String id) {
        //??????????????????sku
        GoodsSku goodsSku = (GoodsSku) cache.get(GoodsSkuService.getCacheKeys(id));
        //?????????????????????????????????????????????????????????????????????
        if (goodsSku == null) {
            goodsSku = this.getById(id);
            if (goodsSku == null) {
                return null;
            }
            cache.put(GoodsSkuService.getCacheKeys(id), goodsSku);
        }

        //??????????????????
        Integer integer = (Integer) cache.get(GoodsSkuService.getStockCacheKey(id));

        //???????????????,???????????????????????????
        if (integer != null && !goodsSku.getQuantity().equals(integer)) {
            //???????????????????????????
            goodsSku.setQuantity(integer);
            cache.put(GoodsSkuService.getCacheKeys(goodsSku.getId()), goodsSku);
        }
        return goodsSku;
    }

    @Override
    public GoodsSku getCanPromotionGoodsSkuByIdFromCache(String skuId) {
        GoodsSku goodsSku = this.getGoodsSkuByIdFromCache(skuId);
        if (goodsSku != null && GoodsSalesModeEnum.WHOLESALE.name().equals(goodsSku.getSalesModel())) {
            throw new ServiceException(ResultCode.PROMOTION_GOODS_DO_NOT_JOIN_WHOLESALE, goodsSku.getGoodsName());
        }
        return goodsSku;
    }

    @Override
    public Map<String, Object> getGoodsSkuDetail(String goodsId, String skuId) {
        Map<String, Object> map = new HashMap<>(16);
        //????????????VO
        GoodsVO goodsVO = goodsService.getGoodsVO(goodsId);
        //??????skuid????????????????????????VO???sku????????????
        if (CharSequenceUtil.isEmpty(skuId) || "undefined".equals(skuId)) {
            skuId = goodsVO.getSkuList().get(0).getId();
        }
        //??????????????????Sku
        GoodsSku goodsSku = this.getGoodsSkuByIdFromCache(skuId);
        //??????????????????ID????????????SKU???????????????
        if (goodsVO == null || goodsSku == null) {
            //??????mq??????
            String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.GOODS_DELETE.name();
            rocketMQTemplate.asyncSend(destination, JSONUtil.toJsonStr(Collections.singletonList(goodsId)), RocketmqSendCallbackBuilder.commonCallback());
            throw new ServiceException(ResultCode.GOODS_NOT_EXIST);
        }

        //????????????||?????????????????????||??????????????????????????????????????????
        if (GoodsStatusEnum.DOWN.name().equals(goodsVO.getMarketEnable()) || !GoodsAuthEnum.PASS.name().equals(goodsVO.getAuthFlag()) || Boolean.TRUE.equals(goodsVO.getDeleteFlag())) {
            String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.GOODS_DELETE.name();
            rocketMQTemplate.asyncSend(destination, JSONUtil.toJsonStr(Collections.singletonList(goodsId)), RocketmqSendCallbackBuilder.commonCallback());
            throw new ServiceException(ResultCode.GOODS_NOT_EXIST);
        }

        //?????????????????????????????????
        EsGoodsIndex goodsIndex = goodsIndexService.findById(skuId);
        if (goodsIndex == null) {
            goodsIndex = goodsIndexService.getResetEsGoodsIndex(goodsSku, goodsVO.getGoodsParamsDTOList());
        }

        //????????????
        GoodsSkuVO goodsSkuDetail = this.getGoodsSkuVO(goodsSku);

        Map<String, Object> promotionMap = goodsIndex.getPromotionMap();
        //?????????????????????????????????
        if (promotionMap != null && !promotionMap.isEmpty()) {
            promotionMap = promotionMap.entrySet().stream().parallel().filter(i -> {
                JSONObject jsonObject = JSONUtil.parseObj(i.getValue());
                // ???????????????????????????????????????????????????
                return (jsonObject.get("getType") == null || jsonObject.get("getType", String.class).equals(CouponGetEnum.FREE.name())) && (jsonObject.get("startTime") != null && jsonObject.get("startTime", Date.class).getTime() <= System.currentTimeMillis()) && (jsonObject.get("endTime") == null || jsonObject.get("endTime", Date.class).getTime() >= System.currentTimeMillis());
            }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            Optional<Map.Entry<String, Object>> containsPromotion = promotionMap.entrySet().stream().filter(i -> i.getKey().contains(PromotionTypeEnum.SECKILL.name()) || i.getKey().contains(PromotionTypeEnum.PINTUAN.name())).findFirst();
            if (containsPromotion.isPresent()) {
                JSONObject jsonObject = JSONUtil.parseObj(containsPromotion.get().getValue());
                PromotionGoodsSearchParams searchParams = new PromotionGoodsSearchParams();
                searchParams.setSkuId(skuId);
                searchParams.setPromotionId(jsonObject.get("id").toString());
                PromotionGoods promotionsGoods = promotionGoodsService.getPromotionsGoods(searchParams);
                if (promotionsGoods != null && promotionsGoods.getPrice() != null) {
                    goodsSkuDetail.setPromotionFlag(true);
                    goodsSkuDetail.setPromotionPrice(promotionsGoods.getPrice());
                }
            } else {
                goodsSkuDetail.setPromotionFlag(false);
                goodsSkuDetail.setPromotionPrice(null);
            }

        }
        map.put("data", goodsSkuDetail);

        //????????????
        String[] split = goodsSkuDetail.getCategoryPath().split(",");
        map.put("wholesaleList", wholesaleService.findByGoodsId(goodsSkuDetail.getGoodsId()));
        map.put("categoryName", categoryService.getCategoryNameByIds(Arrays.asList(split)));

        //??????????????????
        map.put("specs", this.groupBySkuAndSpec(goodsVO.getSkuList()));
        map.put("promotionMap", promotionMap);

        //??????????????????
        if (goodsVO.getGoodsParamsDTOList() != null && !goodsVO.getGoodsParamsDTOList().isEmpty()) {
            map.put("goodsParamsDTOList", goodsVO.getGoodsParamsDTOList());
        }

        //??????????????????
        if (UserContext.getCurrentUser() != null) {
            FootPrint footPrint = new FootPrint(UserContext.getCurrentUser().getId(), goodsIndex.getStoreId(), goodsId, skuId);
            String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.VIEW_GOODS.name();
            rocketMQTemplate.asyncSend(destination, footPrint, RocketmqSendCallbackBuilder.commonCallback());
        }
        return map;
    }

    /**
     * ????????????sku??????
     *
     * @param goods ????????????(Id,MarketEnable/AuthFlag)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGoodsSkuStatus(Goods goods) {
        LambdaUpdateWrapper<GoodsSku> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(CharSequenceUtil.isNotEmpty(goods.getId()), GoodsSku::getGoodsId, goods.getId());
        updateWrapper.eq(CharSequenceUtil.isNotEmpty(goods.getStoreId()), GoodsSku::getStoreId, goods.getStoreId());
        updateWrapper.set(GoodsSku::getMarketEnable, goods.getMarketEnable());
        updateWrapper.set(GoodsSku::getAuthFlag, goods.getAuthFlag());
        updateWrapper.set(GoodsSku::getDeleteFlag, goods.getDeleteFlag());
        boolean update = this.update(updateWrapper);
        if (Boolean.TRUE.equals(update)) {
            List<GoodsSku> goodsSkus = this.getGoodsSkuListByGoodsId(goods.getId());
            for (GoodsSku sku : goodsSkus) {
                cache.remove(GoodsSkuService.getCacheKeys(sku.getId()));
                cache.put(GoodsSkuService.getCacheKeys(sku.getId()), sku);
            }
        }
    }

    /**
     * ????????????sku??????????????????id
     *
     * @param storeId      ??????id
     * @param marketEnable ??????????????????
     * @param authFlag     ????????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGoodsSkuStatusByStoreId(String storeId, String marketEnable, String authFlag) {
        LambdaUpdateWrapper<GoodsSku> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(GoodsSku::getStoreId, storeId);
        updateWrapper.set(CharSequenceUtil.isNotEmpty(marketEnable), GoodsSku::getMarketEnable, marketEnable);
        updateWrapper.set(CharSequenceUtil.isNotEmpty(authFlag), GoodsSku::getAuthFlag, authFlag);
        boolean update = this.update(updateWrapper);
        if (Boolean.TRUE.equals(update)) {
            if (GoodsStatusEnum.UPPER.name().equals(marketEnable)) {
                applicationEventPublisher.publishEvent(new TransactionCommitSendMQEvent("??????????????????", rocketmqCustomProperties.getGoodsTopic(), GoodsTagsEnum.GENERATOR_STORE_GOODS_INDEX.name(), storeId));
            } else if (GoodsStatusEnum.DOWN.name().equals(marketEnable)) {
                cache.vagueDel(CachePrefix.GOODS_SKU.getPrefix());
                applicationEventPublisher.publishEvent(new TransactionCommitSendMQEvent("??????????????????", rocketmqCustomProperties.getGoodsTopic(), GoodsTagsEnum.STORE_GOODS_DELETE.name(), storeId));
            }
        }
    }

    @Override
    public List<GoodsSku> getGoodsSkuByIdFromCache(List<String> ids) {
        List<String> keys = new ArrayList<>();
        for (String id : ids) {
            keys.add(GoodsSkuService.getCacheKeys(id));
        }
        List<GoodsSku> list = cache.multiGet(keys);
        if (list == null || list.isEmpty()) {
            list = new ArrayList<>();
            List<GoodsSku> goodsSkus = listByIds(ids);
            for (GoodsSku skus : goodsSkus) {
                cache.put(GoodsSkuService.getCacheKeys(skus.getId()), skus);
                list.add(skus);
            }
        }
        return list;
    }

    @Override
    public List<GoodsSkuVO> getGoodsListByGoodsId(String goodsId) {
        List<GoodsSku> list = this.list(new LambdaQueryWrapper<GoodsSku>().eq(GoodsSku::getGoodsId, goodsId));
        return this.getGoodsSkuVOList(list);
    }

    /**
     * ??????goodsId????????????goodsSku
     *
     * @param goodsId ??????id
     * @return goodsSku??????
     */
    @Override
    public List<GoodsSku> getGoodsSkuListByGoodsId(String goodsId) {
        return this.list(new LambdaQueryWrapper<GoodsSku>().eq(GoodsSku::getGoodsId, goodsId));
    }

    @Override
    public List<GoodsSkuVO> getGoodsSkuVOList(List<GoodsSku> list) {
        List<GoodsSkuVO> goodsSkuVOS = new ArrayList<>();
        for (GoodsSku goodsSku : list) {
            GoodsSkuVO goodsSkuVO = this.getGoodsSkuVO(goodsSku);
            goodsSkuVOS.add(goodsSkuVO);
        }
        return goodsSkuVOS;
    }

    @Override
    public GoodsSkuVO getGoodsSkuVO(GoodsSku goodsSku) {
        //???????????????
        GoodsSkuVO goodsSkuVO = new GoodsSkuVO(goodsSku);
        //??????sku??????
        JSONObject jsonObject = JSONUtil.parseObj(goodsSku.getSpecs());
        //????????????sku??????
        List<SpecValueVO> specValueVOS = new ArrayList<>();
        //????????????sku??????
        List<String> goodsGalleryList = new ArrayList<>();
        //???????????????sku??????
        for (Map.Entry<String, Object> entry : jsonObject.entrySet()) {
            SpecValueVO specValueVO = new SpecValueVO();
            if ("images".equals(entry.getKey())) {
                specValueVO.setSpecName(entry.getKey());
                if (entry.getValue().toString().contains("url")) {
                    List<SpecValueVO.SpecImages> specImages = JSONUtil.toList(JSONUtil.parseArray(entry.getValue()), SpecValueVO.SpecImages.class);
                    specValueVO.setSpecImage(specImages);
                    goodsGalleryList = specImages.stream().map(SpecValueVO.SpecImages::getUrl).collect(Collectors.toList());
                }
            } else {
                specValueVO.setSpecName(entry.getKey());
                specValueVO.setSpecValue(entry.getValue().toString());
            }
            specValueVOS.add(specValueVO);
        }
        goodsSkuVO.setGoodsGalleryList(goodsGalleryList);
        goodsSkuVO.setSpecList(specValueVOS);
        return goodsSkuVO;
    }

    @Override
    public IPage<GoodsSku> getGoodsSkuByPage(GoodsSearchParams searchParams) {
        return this.page(PageUtil.initPage(searchParams), searchParams.queryWrapper());
    }

    @Override
    public IPage<GoodsSkuDTO> getGoodsSkuDTOByPage(Page<GoodsSkuDTO> page, Wrapper<GoodsSkuDTO> queryWrapper) {
        return this.baseMapper.queryByParams(page, queryWrapper);
    }

    /**
     * ??????????????????sku??????
     *
     * @param searchParams ????????????
     * @return ??????sku??????
     */
    @Override
    public List<GoodsSku> getGoodsSkuByList(GoodsSearchParams searchParams) {
        return this.list(searchParams.queryWrapper());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStocks(List<GoodsSkuStockDTO> goodsSkuStockDTOS) {
        for (GoodsSkuStockDTO goodsSkuStockDTO : goodsSkuStockDTOS) {
            this.updateStock(goodsSkuStockDTO.getSkuId(), goodsSkuStockDTO.getQuantity());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStock(String skuId, Integer quantity) {
        GoodsSku goodsSku = getGoodsSkuByIdFromCache(skuId);
        if (goodsSku != null) {
            if (quantity <= 0) {
                goodsIndexService.deleteIndexById(goodsSku.getId());
            }
            goodsSku.setQuantity(quantity);
            boolean update = this.update(new LambdaUpdateWrapper<GoodsSku>().eq(GoodsSku::getId, skuId).set(GoodsSku::getQuantity, quantity));
            if (update) {
                cache.remove(CachePrefix.GOODS.getPrefix() + goodsSku.getGoodsId());
            }
            cache.put(GoodsSkuService.getCacheKeys(skuId), goodsSku);
            cache.put(GoodsSkuService.getStockCacheKey(skuId), quantity);

            //??????????????????
            List<GoodsSku> goodsSkus = new ArrayList<>();
            goodsSkus.add(goodsSku);
            this.updateGoodsStuck(goodsSkus);
            this.promotionGoodsService.updatePromotionGoodsStock(goodsSku.getId(), quantity);
        }
    }

    @Override
    public Integer getStock(String skuId) {
        String cacheKeys = GoodsSkuService.getStockCacheKey(skuId);
        Integer stock = (Integer) cache.get(cacheKeys);
        if (stock != null) {
            return stock;
        } else {
            GoodsSku goodsSku = getGoodsSkuByIdFromCache(skuId);
            cache.put(cacheKeys, goodsSku.getQuantity());
            return goodsSku.getQuantity();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGoodsStuck(List<GoodsSku> goodsSkus) {
        Map<String, List<GoodsSku>> groupByGoodsIds = goodsSkus.stream().collect(Collectors.groupingBy(GoodsSku::getGoodsId));
        //???????????????sku??????
        LambdaQueryWrapper<GoodsSku> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.in(GoodsSku::getGoodsId, groupByGoodsIds.keySet());
        List<GoodsSku> goodsSkuList = this.list(lambdaQueryWrapper);

        //???????????????????????????
        for (String goodsId : groupByGoodsIds.keySet()) {
            //??????
            Integer quantity = 0;
            for (GoodsSku goodsSku : goodsSkuList) {
                if (goodsId.equals(goodsSku.getGoodsId())) {
                    quantity += goodsSku.getQuantity();
                }
            }
            //????????????????????????
            goodsService.updateStock(goodsId, quantity);
        }


    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGoodsSkuCommentNum(String skuId) {
        //??????????????????
        GoodsSku goodsSku = this.getGoodsSkuByIdFromCache(skuId);

        EvaluationQueryParams queryParams = new EvaluationQueryParams();
        queryParams.setGrade(EvaluationGradeEnum.GOOD.name());
        queryParams.setSkuId(goodsSku.getId());
        //????????????
        long highPraiseNum = memberEvaluationService.getEvaluationCount(queryParams);

        //????????????????????????
        goodsSku.setCommentNum(goodsSku.getCommentNum() != null ? goodsSku.getCommentNum() + 1 : 1);

        //?????????
        double grade = NumberUtil.mul(NumberUtil.div(highPraiseNum, goodsSku.getCommentNum().doubleValue(), 2), 100);
        goodsSku.setGrade(grade);
        //????????????
        this.update(goodsSku);


        //??????????????????,??????mq??????
        Map<String, Object> updateIndexFieldsMap = EsIndexUtil.getUpdateIndexFieldsMap(MapUtil.builder(new HashMap<String, Object>()).put("id", goodsSku.getId()).build(), MapUtil.builder(new HashMap<String, Object>()).put("commentNum", goodsSku.getCommentNum()).put("highPraiseNum", highPraiseNum).put("grade", grade).build());
        String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.UPDATE_GOODS_INDEX_FIELD.name();
        rocketMQTemplate.asyncSend(destination, JSONUtil.toJsonStr(updateIndexFieldsMap), RocketmqSendCallbackBuilder.commonCallback());

        //???????????????????????????
        goodsService.updateGoodsCommentNum(goodsSku.getGoodsId());
    }

    /**
     * ????????????id????????????skuId?????????
     *
     * @param goodsId goodsId
     * @return ??????skuId?????????
     */
    @Override
    public List<String> getSkuIdsByGoodsId(String goodsId) {
        return this.baseMapper.getGoodsSkuIdByGoodsId(goodsId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteAndInsertGoodsSkus(List<GoodsSku> goodsSkus) {
        int count = 0;
        for (GoodsSku skus : goodsSkus) {
            if (CharSequenceUtil.isEmpty(skus.getId())) {
                skus.setId(SnowFlake.getIdStr());
            }
            count = this.baseMapper.replaceGoodsSku(skus);
        }
        return count > 0;
    }

    @Override
    public Long countSkuNum(String storeId) {
        LambdaQueryWrapper<GoodsSku> queryWrapper = new LambdaQueryWrapper<>();

        queryWrapper
                .eq(GoodsSku::getStoreId, storeId)
                .eq(GoodsSku::getDeleteFlag, Boolean.FALSE)
                .eq(GoodsSku::getAuthFlag, GoodsAuthEnum.PASS.name())
                .eq(GoodsSku::getMarketEnable, GoodsStatusEnum.UPPER.name());
        return this.count(queryWrapper);
    }

    /**
     * ????????????
     *
     * @param goodsSkus ??????SKU
     */
    private void updateStock(List<GoodsSku> goodsSkus) {
        //???????????????
        Integer quantity = 0;
        for (GoodsSku sku : goodsSkus) {
            this.updateStock(sku.getId(), sku.getQuantity());
            quantity += sku.getQuantity();
        }
        //??????????????????
        goodsService.updateStock(goodsSkus.get(0).getGoodsId(), quantity);
    }


    /**
     * ??????????????????sku
     *
     * @param goodsSkuList      sku??????
     * @param goodsOperationDTO ????????????DTO
     */
    @Override
    public void renderGoodsSkuList(List<GoodsSku> goodsSkuList, GoodsOperationDTO goodsOperationDTO) {
        // ???????????????????????????
        salesModelRenders.stream().filter(i -> i.getSalesMode().equals(goodsOperationDTO.getSalesModel())).findFirst().ifPresent(i -> i.renderBatch(goodsSkuList, goodsOperationDTO));
        for (GoodsSku goodsSku : goodsSkuList) {
            extendOldSkuValue(goodsSku);
            this.renderImages(goodsSku);
        }
    }

    /**
     * ????????????sku
     *
     * @param goodsSku          sku
     * @param goodsOperationDTO ????????????DTO
     */
    void renderGoodsSku(GoodsSku goodsSku, GoodsOperationDTO goodsOperationDTO) {
        extendOldSkuValue(goodsSku);
        // ???????????????????????????
        salesModelRenders.stream().filter(i -> i.getSalesMode().equals(goodsOperationDTO.getSalesModel())).findFirst().ifPresent(i -> i.renderSingle(goodsSku, goodsOperationDTO));
        this.renderImages(goodsSku);
    }

    /**
     * ??????sku?????????????????????????????????????????????sku???
     *
     * @param goodsSku ??????sku
     */
    private void extendOldSkuValue(GoodsSku goodsSku) {
        if (CharSequenceUtil.isNotEmpty(goodsSku.getGoodsId())) {
            GoodsSku oldSku = this.getGoodsSkuByIdFromCache(goodsSku.getId());
            if (oldSku != null) {
                goodsSku.setCommentNum(oldSku.getCommentNum());
                goodsSku.setViewCount(oldSku.getViewCount());
                goodsSku.setBuyCount(oldSku.getBuyCount());
                goodsSku.setGrade(oldSku.getGrade());
            }
        }
    }

    /**
     * ??????sku??????
     *
     * @param goodsSku sku
     */
    void renderImages(GoodsSku goodsSku) {
        JSONObject jsonObject = JSONUtil.parseObj(goodsSku.getSpecs());
        List<Map<String, String>> images = jsonObject.get("images", List.class);
        if (images != null && !images.isEmpty()) {
            GoodsGallery goodsGallery = goodsGalleryService.getGoodsGallery(images.get(0).get("url"));
            goodsSku.setBig(goodsGallery.getOriginal());
            goodsSku.setOriginal(goodsGallery.getOriginal());
            goodsSku.setThumbnail(goodsGallery.getThumbnail());
            goodsSku.setSmall(goodsGallery.getSmall());
        }
    }

    /**
     * ????????????????????????sku??????????????????
     *
     * @param goodsSkuVOList ??????VO??????
     * @return ??????????????????sku??????????????????
     */
    private List<GoodsSkuSpecVO> groupBySkuAndSpec(List<GoodsSkuVO> goodsSkuVOList) {

        List<GoodsSkuSpecVO> skuSpecVOList = new ArrayList<>();
        for (GoodsSkuVO goodsSkuVO : goodsSkuVOList) {
            GoodsSkuSpecVO specVO = new GoodsSkuSpecVO();
            specVO.setSkuId(goodsSkuVO.getId());
            specVO.setSpecValues(goodsSkuVO.getSpecList());
            specVO.setQuantity(goodsSkuVO.getQuantity());
            skuSpecVOList.add(specVO);
        }
        return skuSpecVOList;
    }

}

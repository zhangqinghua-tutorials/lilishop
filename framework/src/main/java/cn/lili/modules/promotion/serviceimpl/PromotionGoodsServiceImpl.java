package cn.lili.modules.promotion.serviceimpl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.lili.cache.Cache;
import cn.lili.common.enums.PromotionTypeEnum;
import cn.lili.common.vo.PageVO;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.entity.dto.GoodsSkuDTO;
import cn.lili.modules.goods.entity.vos.GoodsVO;
import cn.lili.modules.goods.service.GoodsService;
import cn.lili.modules.goods.service.GoodsSkuService;
import cn.lili.modules.order.cart.entity.enums.CartTypeEnum;
import cn.lili.modules.promotion.entity.dos.PromotionGoods;
import cn.lili.modules.promotion.entity.dos.SeckillApply;
import cn.lili.modules.promotion.entity.dto.search.PromotionGoodsSearchParams;
import cn.lili.modules.promotion.entity.dto.search.SeckillSearchParams;
import cn.lili.modules.promotion.entity.enums.PromotionsScopeTypeEnum;
import cn.lili.modules.promotion.entity.enums.PromotionsStatusEnum;
import cn.lili.modules.promotion.mapper.PromotionGoodsMapper;
import cn.lili.modules.promotion.service.PromotionGoodsService;
import cn.lili.modules.promotion.service.SeckillApplyService;
import cn.lili.modules.promotion.tools.PromotionTools;
import cn.lili.modules.search.entity.dos.EsGoodsIndex;
import cn.lili.modules.search.service.EsGoodsIndexService;
import cn.lili.mybatis.util.PageUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ???????????????????????????
 *
 * @author Chopper
 * @since 2021/3/18 9:22 ??????
 */
@Service
public class PromotionGoodsServiceImpl extends ServiceImpl<PromotionGoodsMapper, PromotionGoods> implements PromotionGoodsService {

    private static final String SKU_ID_COLUMN = "sku_id";

    /**
     * Redis
     */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /**
     * ??????????????????
     */
    @Autowired
    private SeckillApplyService seckillApplyService;
    /**
     * ????????????
     */
    @Autowired
    private GoodsSkuService goodsSkuService;

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private EsGoodsIndexService goodsIndexService;

    @Autowired
    private Cache cache;

    @Override
    public List<PromotionGoods> findSkuValidPromotion(String skuId, String storeIds) {

        GoodsSku sku = goodsSkuService.getGoodsSkuByIdFromCache(skuId);
        if (sku == null) {
            return new ArrayList<>();
        }
        QueryWrapper<PromotionGoods> queryWrapper = new QueryWrapper<>();

        queryWrapper.and(i -> i.or(j -> j.eq(SKU_ID_COLUMN, skuId))
                .or(n -> n.eq("scope_type", PromotionsScopeTypeEnum.ALL.name()))
                .or(n -> n.and(k -> k.eq("scope_type", PromotionsScopeTypeEnum.PORTION_GOODS_CATEGORY.name())
                        .and(l -> l.like("scope_id", sku.getCategoryPath())))));
        queryWrapper.and(i -> i.or(PromotionTools.queryPromotionStatus(PromotionsStatusEnum.START)).or(PromotionTools.queryPromotionStatus(PromotionsStatusEnum.NEW)));
        queryWrapper.in("store_id", Arrays.asList(storeIds.split(",")));
        return this.list(queryWrapper);
    }

    @Override
    public List<PromotionGoods> findSkuValidPromotions(List<GoodsSkuDTO> skus) {
        List<String> categories = skus.stream().map(GoodsSku::getCategoryPath).collect(Collectors.toList());
        List<String> skuIds = skus.stream().map(GoodsSku::getId).collect(Collectors.toList());
        List<String> categoriesPath = new ArrayList<>();
        categories.forEach(i -> {
                    if (CharSequenceUtil.isNotEmpty(i)) {
                        categoriesPath.addAll(Arrays.asList(i.split(",")));
                    }
                }
        );
        QueryWrapper<PromotionGoods> queryWrapper = new QueryWrapper<>();

        queryWrapper.and(i -> i.or(j -> j.in(SKU_ID_COLUMN, skuIds))
                .or(n -> n.eq("scope_type", PromotionsScopeTypeEnum.ALL.name()))
                .or(n -> n.and(k -> k.eq("scope_type", PromotionsScopeTypeEnum.PORTION_GOODS_CATEGORY.name())
                        .and(l -> l.in("scope_id", categoriesPath)))));
        queryWrapper.and(i -> i.or(PromotionTools.queryPromotionStatus(PromotionsStatusEnum.START)).or(PromotionTools.queryPromotionStatus(PromotionsStatusEnum.NEW)));
        return this.list(queryWrapper);
    }

    @Override
    public Page<PromotionGoods> pageFindAll(PromotionGoodsSearchParams searchParams, PageVO pageVo) {
        return this.page(PageUtil.initPage(pageVo), searchParams.queryWrapper());
    }

    /**
     * ????????????????????????
     *
     * @param searchParams ????????????
     * @return ??????????????????
     */
    @Override
    public List<PromotionGoods> listFindAll(PromotionGoodsSearchParams searchParams) {
        return this.list(searchParams.queryWrapper());
    }

    /**
     * ????????????????????????
     *
     * @param searchParams ????????????
     * @return ??????????????????
     */
    @Override
    public PromotionGoods getPromotionsGoods(PromotionGoodsSearchParams searchParams) {
        return this.getOne(searchParams.queryWrapper(), false);
    }

    /**
     * ???????????????????????????????????????????????????????????????
     *
     * @param skuId          ????????????
     * @param promotionTypes ??????????????????
     * @return ??????????????????
     */
    @Override
    public PromotionGoods getValidPromotionsGoods(String skuId, List<String> promotionTypes) {
        QueryWrapper<PromotionGoods> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(SKU_ID_COLUMN, skuId);
        queryWrapper.in("promotion_type", promotionTypes);
        queryWrapper.and(PromotionTools.queryPromotionStatus(PromotionsStatusEnum.START));
        return this.getOne(queryWrapper, false);
    }

    /**
     * ???????????????????????????????????????????????????????????????
     *
     * @param skuId          skuId
     * @param promotionTypes ??????????????????
     * @return ??????????????????
     */
    @Override
    public Double getValidPromotionsGoodsPrice(String skuId, List<String> promotionTypes) {
        QueryWrapper<PromotionGoods> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(SKU_ID_COLUMN, skuId);
        queryWrapper.in("promotion_type", promotionTypes);
        queryWrapper.and(PromotionTools.queryPromotionStatus(PromotionsStatusEnum.START));
        return this.baseMapper.selectPromotionsGoodsPrice(queryWrapper);
    }

    @Override
    public Integer findInnerOverlapPromotionGoods(String promotionType, String skuId, Date startTime, Date endTime, String promotionId) {
        if (promotionId != null) {
            return this.baseMapper.selectInnerOverlapPromotionGoodsWithout(promotionType, skuId, startTime, endTime, promotionId);
        } else {
            return this.baseMapper.selectInnerOverlapPromotionGoods(promotionType, skuId, startTime, endTime);
        }
    }

    /**
     * ??????????????????????????????
     *
     * @param typeEnum    ??????????????????
     * @param promotionId ????????????id
     * @param skuId       ??????skuId
     * @return ????????????????????????
     */
    @Override
    public Integer getPromotionGoodsStock(PromotionTypeEnum typeEnum, String promotionId, String skuId) {
        String promotionStockKey = PromotionGoodsService.getPromotionGoodsStockCacheKey(typeEnum, promotionId, skuId);
        String promotionGoodsStock = stringRedisTemplate.opsForValue().get(promotionStockKey);

        //???????????????????????????????????????
        if (promotionGoodsStock != null && CharSequenceUtil.isNotEmpty(promotionGoodsStock)) {
            return Convert.toInt(promotionGoodsStock);
        }
        //????????????
        else {
            //????????????????????????????????????????????????????????????0
            PromotionGoodsSearchParams searchParams = new PromotionGoodsSearchParams();
            searchParams.setPromotionType(typeEnum.name());
            searchParams.setPromotionId(promotionId);
            searchParams.setSkuId(skuId);
            PromotionGoods promotionGoods = this.getPromotionsGoods(searchParams);
            if (promotionGoods == null) {
                return 0;
            }
            //????????????????????????????????????
            stringRedisTemplate.opsForValue().set(promotionStockKey, promotionGoods.getQuantity().toString());
            return promotionGoods.getQuantity();
        }
    }

    @Override
    public List<Integer> getPromotionGoodsStock(PromotionTypeEnum typeEnum, String promotionId, List<String> skuId) {
        PromotionGoodsSearchParams searchParams = new PromotionGoodsSearchParams();
        searchParams.setPromotionType(typeEnum.name());
        searchParams.setPromotionId(promotionId);
        searchParams.setSkuIds(skuId);
        //????????????????????????????????????????????????????????????0
        List<PromotionGoods> promotionGoods = this.listFindAll(searchParams);
        //????????????
        List<Integer> result = new ArrayList<>(skuId.size());
        for (String sid : skuId) {
            Integer stock = null;
            for (PromotionGoods pg : promotionGoods) {
                if (sid.equals(pg.getSkuId())) {
                    stock = pg.getQuantity();
                }
            }
            //????????????????????????????????????????????????
            if (stock == null) {
                stock = 0;
            }
            result.add(stock);
        }
        return result;
    }

    /**
     * ??????????????????????????????
     *
     * @param promotionGoodsList ??????????????????????????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePromotionGoodsStock(List<PromotionGoods> promotionGoodsList) {
        for (PromotionGoods promotionGoods : promotionGoodsList) {
            String promotionStockKey = PromotionGoodsService.getPromotionGoodsStockCacheKey(PromotionTypeEnum.valueOf(promotionGoods.getPromotionType()), promotionGoods.getPromotionId(), promotionGoods.getSkuId());
            if (promotionGoods.getPromotionType().equals(PromotionTypeEnum.SECKILL.name())) {
                SeckillSearchParams searchParams = new SeckillSearchParams();
                searchParams.setSeckillId(promotionGoods.getPromotionId());
                searchParams.setSkuId(promotionGoods.getSkuId());
                SeckillApply seckillApply = this.seckillApplyService.getSeckillApply(searchParams);
                if (seckillApply != null) {
                    seckillApplyService.updateSeckillApplySaleNum(promotionGoods.getPromotionId(), promotionGoods.getSkuId(), promotionGoods.getNum());
                }
            }

            LambdaUpdateWrapper<PromotionGoods> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(PromotionGoods::getPromotionType, promotionGoods.getPromotionType()).eq(PromotionGoods::getPromotionId, promotionGoods.getPromotionId()).eq(PromotionGoods::getSkuId, promotionGoods.getSkuId());
            updateWrapper.set(PromotionGoods::getQuantity, promotionGoods.getQuantity()).set(PromotionGoods::getNum, promotionGoods.getNum());

            this.update(updateWrapper);
            stringRedisTemplate.opsForValue().set(promotionStockKey, promotionGoods.getQuantity().toString());
        }
    }

    @Override
    public void updatePromotionGoodsStock(String skuId, Integer quantity) {
        LambdaQueryWrapper<PromotionGoods> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PromotionGoods::getSkuId, skuId);
        this.list(queryWrapper).forEach(promotionGoods -> {
            String promotionStockKey = PromotionGoodsService.getPromotionGoodsStockCacheKey(PromotionTypeEnum.valueOf(promotionGoods.getPromotionType()), promotionGoods.getPromotionId(), promotionGoods.getSkuId());
            cache.remove(promotionStockKey);
        });
        LambdaUpdateWrapper<PromotionGoods> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PromotionGoods::getSkuId, skuId);
        updateWrapper.set(PromotionGoods::getQuantity, quantity);
        this.update(updateWrapper);
    }

    /**
     * ??????????????????????????????
     *
     * @param promotionGoods ????????????
     */
    @Override
    public void updatePromotionGoodsByPromotions(PromotionGoods promotionGoods) {
        LambdaQueryWrapper<PromotionGoods> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PromotionGoods::getPromotionId, promotionGoods.getPromotionId());
        this.remove(queryWrapper);
        this.save(promotionGoods);
    }

    /**
     * ??????????????????
     *
     * @param promotionId ????????????id
     * @param skuIds      skuId
     */
    @Override
    public void deletePromotionGoods(String promotionId, List<String> skuIds) {
        LambdaQueryWrapper<PromotionGoods> queryWrapper = new LambdaQueryWrapper<PromotionGoods>()
                .eq(PromotionGoods::getPromotionId, promotionId).in(PromotionGoods::getSkuId, skuIds);
        this.remove(queryWrapper);
    }

    /**
     * ????????????????????????
     *
     * @param promotionIds ????????????id
     */
    @Override
    public void deletePromotionGoods(List<String> promotionIds) {
        LambdaQueryWrapper<PromotionGoods> queryWrapper = new LambdaQueryWrapper<PromotionGoods>().in(PromotionGoods::getPromotionId, promotionIds);
        this.remove(queryWrapper);
    }

    @Override
    public void deletePromotionGoodsByGoods(List<String> goodsIds) {
        LambdaQueryWrapper<PromotionGoods> queryWrapper = new LambdaQueryWrapper<PromotionGoods>().in(PromotionGoods::getGoodsId, goodsIds);
        this.remove(queryWrapper);
    }


    /**
     * ??????????????????????????????
     *
     * @param searchParams ????????????
     */
    @Override
    public void deletePromotionGoods(PromotionGoodsSearchParams searchParams) {
        this.remove(searchParams.queryWrapper());
    }

    @Override
    public Map<String, Object> getCurrentGoodsPromotion(GoodsSku dataSku, String cartType) {
        Map<String, Object> promotionMap;
        EsGoodsIndex goodsIndex = goodsIndexService.findById(dataSku.getId());
        if (goodsIndex == null) {
            GoodsVO goodsVO = this.goodsService.getGoodsVO(dataSku.getGoodsId());
            goodsIndex = goodsIndexService.getResetEsGoodsIndex(dataSku, goodsVO.getGoodsParamsDTOList());
        }
        if (goodsIndex.getPromotionMap() != null && !goodsIndex.getPromotionMap().isEmpty()) {
            if (goodsIndex.getPromotionMap().keySet().stream().anyMatch(i -> i.contains(PromotionTypeEnum.SECKILL.name())) || (goodsIndex.getPromotionMap().keySet().stream().anyMatch(i -> i.contains(PromotionTypeEnum.PINTUAN.name())) && CartTypeEnum.PINTUAN.name().equals(cartType))) {
                Optional<Map.Entry<String, Object>> containsPromotion = goodsIndex.getPromotionMap().entrySet().stream().filter(i -> i.getKey().contains(PromotionTypeEnum.SECKILL.name()) || i.getKey().contains(PromotionTypeEnum.PINTUAN.name())).findFirst();
                containsPromotion.ifPresent(stringObjectEntry -> this.setGoodsPromotionInfo(dataSku, stringObjectEntry));
            }
            promotionMap = goodsIndex.getPromotionMap();
        } else {
            promotionMap = null;
            dataSku.setPromotionFlag(false);
            dataSku.setPromotionPrice(null);
        }
        return promotionMap;
    }

    private void setGoodsPromotionInfo(GoodsSku dataSku, Map.Entry<String, Object> promotionInfo) {
        JSONObject promotionsObj = JSONUtil.parseObj(promotionInfo.getValue());
        PromotionGoodsSearchParams searchParams = new PromotionGoodsSearchParams();
        searchParams.setSkuId(dataSku.getId());
        searchParams.setPromotionId(promotionsObj.get("id").toString());
        PromotionGoods promotionsGoods = this.getPromotionsGoods(searchParams);
        if (promotionsGoods != null && promotionsGoods.getPrice() != null) {
            dataSku.setPromotionFlag(true);
            dataSku.setPromotionPrice(promotionsGoods.getPrice());
        } else {
            dataSku.setPromotionFlag(false);
            dataSku.setPromotionPrice(null);
        }
    }

}
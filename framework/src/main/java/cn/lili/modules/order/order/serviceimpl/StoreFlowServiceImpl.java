package cn.lili.modules.order.order.serviceimpl;

import cn.lili.common.utils.*;
import cn.lili.common.vo.PageVO;
import cn.lili.modules.order.aftersale.entity.dos.AfterSale;
import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.entity.dos.StoreFlow;
import cn.lili.modules.order.order.entity.enums.FlowTypeEnum;
import cn.lili.modules.order.order.entity.enums.OrderPromotionTypeEnum;
import cn.lili.modules.order.order.entity.enums.PayStatusEnum;
import cn.lili.modules.order.order.mapper.StoreFlowMapper;
import cn.lili.modules.order.order.service.OrderItemService;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.order.order.service.StoreFlowService;
import cn.lili.modules.payment.entity.RefundLog;
import cn.lili.modules.payment.service.RefundLogService;
import cn.lili.modules.store.entity.dos.Bill;
import cn.lili.modules.store.entity.vos.StoreFlowPayDownloadVO;
import cn.lili.modules.store.entity.vos.StoreFlowRefundDownloadVO;
import cn.lili.modules.store.service.BillService;
import cn.lili.mybatis.util.PageUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 商家订单流水业务层实现
 *
 * @author Chopper
 * @since 2020/11/17 7:38 下午
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class StoreFlowServiceImpl extends ServiceImpl<StoreFlowMapper, StoreFlow> implements StoreFlowService {

    /**
     * 订单
     */
    @Autowired
    private OrderService orderService;
    /**
     * 订单货物
     */
    @Autowired
    private OrderItemService orderItemService;
    /**
     * 退款日志
     */
    @Autowired
    private RefundLogService refundLogService;

    @Autowired
    private BillService billService;

    @Override
    public void payOrder(String orderSn) {
        //根据订单编号获取子订单列表
        List<OrderItem> orderItems = orderItemService.getByOrderSn(orderSn);
        //根据订单编号获取订单数据
        Order order = orderService.getBySn(orderSn);

        //如果查询到多条支付记录，打印日志
        if (order.getPayStatus().equals(PayStatusEnum.PAID.name())) {
            log.error("订单[{}]检测到重复付款，请处理", orderSn);
        }

        //获取订单促销类型,如果为促销订单则获取促销商品并获取结算价
        String orderPromotionType = order.getOrderPromotionType();
        //循环子订单记录流水
        for (OrderItem item : orderItems) {
            StoreFlow storeFlow = new StoreFlow();
            BeanUtil.copyProperties(item, storeFlow);

            //入账
            storeFlow.setId(SnowFlake.getIdStr());
            storeFlow.setFlowType(FlowTypeEnum.PAY.name());
            storeFlow.setSn(SnowFlake.createStr("SF"));
            storeFlow.setOrderSn(item.getOrderSn());
            storeFlow.setOrderItemSn(item.getSn());
            storeFlow.setStoreId(order.getStoreId());
            storeFlow.setStoreName(order.getStoreName());
            storeFlow.setMemberId(order.getMemberId());
            storeFlow.setMemberName(order.getMemberName());
            storeFlow.setGoodsName(item.getGoodsName());

            storeFlow.setOrderPromotionType(item.getPromotionType());

            //计算平台佣金
            storeFlow.setFinalPrice(item.getPriceDetailDTO().getFlowPrice());
            storeFlow.setCommissionPrice(item.getPriceDetailDTO().getPlatFormCommission());
            storeFlow.setDistributionRebate(item.getPriceDetailDTO().getDistributionCommission());
            storeFlow.setBillPrice(item.getPriceDetailDTO().getBillPrice());
            //兼容为空，以及普通订单操作
            if (StringUtils.isNotEmpty(orderPromotionType)) {
                if (orderPromotionType.equals(OrderPromotionTypeEnum.NORMAL.name())) {
                    //普通订单操作
                }
                //如果为砍价活动，填写砍价结算价
                else if (orderPromotionType.equals(OrderPromotionTypeEnum.KANJIA.name())) {
                    storeFlow.setKanjiaSettlementPrice(item.getPriceDetailDTO().getSettlementPrice());
                }
                //如果为砍价活动，填写砍价结算价
                else if (orderPromotionType.equals(OrderPromotionTypeEnum.POINTS.name())) {
                    storeFlow.setPointSettlementPrice(item.getPriceDetailDTO().getSettlementPrice());
                }
            }
            //添加支付方式
            storeFlow.setPaymentName(order.getPaymentMethod());
            //添加第三方支付流水号
            storeFlow.setTransactionId(order.getReceivableNo());

            //添加付款交易流水
            this.save(storeFlow);
        }
    }

    @Override
    public void refundOrder(AfterSale afterSale) {
        StoreFlow storeFlow = new StoreFlow();
        //退款
        storeFlow.setFlowType(FlowTypeEnum.REFUND.name());
        storeFlow.setSn(SnowFlake.createStr("SF"));
        storeFlow.setRefundSn(afterSale.getSn());
        storeFlow.setOrderSn(afterSale.getOrderSn());
        storeFlow.setOrderItemSn(afterSale.getOrderItemSn());
        storeFlow.setStoreId(afterSale.getStoreId());
        storeFlow.setStoreName(afterSale.getStoreName());
        storeFlow.setMemberId(afterSale.getMemberId());
        storeFlow.setMemberName(afterSale.getMemberName());
        storeFlow.setGoodsId(afterSale.getGoodsId());
        storeFlow.setGoodsName(afterSale.getGoodsName());
        storeFlow.setSkuId(afterSale.getSkuId());
        storeFlow.setImage(afterSale.getGoodsImage());
        storeFlow.setSpecs(afterSale.getSpecs());


        //获取付款信息
        StoreFlow payStoreFlow = this.getOne(new LambdaUpdateWrapper<StoreFlow>().eq(StoreFlow::getOrderItemSn, afterSale.getOrderItemSn()));
        storeFlow.setNum(afterSale.getNum());
        storeFlow.setCategoryId(payStoreFlow.getCategoryId());
        //佣金
        storeFlow.setCommissionPrice(CurrencyUtil.mul(CurrencyUtil.div(payStoreFlow.getCommissionPrice(), payStoreFlow.getNum()), afterSale.getNum()));
        //分销佣金
        storeFlow.setDistributionRebate(CurrencyUtil.mul(CurrencyUtil.div(payStoreFlow.getDistributionRebate(), payStoreFlow.getNum()), afterSale.getNum()));
        //流水金额
        storeFlow.setFinalPrice(afterSale.getActualRefundPrice());
        //最终结算金额
        storeFlow.setBillPrice(CurrencyUtil.add(CurrencyUtil.add(storeFlow.getFinalPrice(), storeFlow.getDistributionRebate()), storeFlow.getCommissionPrice()));
        //获取第三方支付流水号
        RefundLog refundLog = refundLogService.getOne(new LambdaQueryWrapper<RefundLog>().eq(RefundLog::getAfterSaleNo, afterSale.getSn()));
        storeFlow.setTransactionId(refundLog.getReceivableNo());
        storeFlow.setPaymentName(refundLog.getPaymentName());
        this.save(storeFlow);
    }

    @Override
    public IPage<StoreFlow> getStoreFlow(String storeId, String type, boolean distribution, PageVO pageVO, Date startTime, Date endTime) {

        LambdaQueryWrapper<StoreFlow> lambdaQueryWrapper = Wrappers.lambdaQuery();
        lambdaQueryWrapper.eq(StoreFlow::getStoreId, storeId);
        lambdaQueryWrapper.isNotNull(distribution, StoreFlow::getDistributionRebate);
        lambdaQueryWrapper.between(StoreFlow::getCreateTime, startTime, endTime);
        lambdaQueryWrapper.eq(StringUtils.isNotEmpty(type), StoreFlow::getFlowType, type);
        return this.page(PageUtil.initPage(pageVO), lambdaQueryWrapper);
    }

    @Override
    public List<StoreFlowPayDownloadVO> getStoreFlowPayDownloadVO(Wrapper<StoreFlow> queryWrapper) {
        return baseMapper.getStoreFlowPayDownloadVO(queryWrapper);
    }

    @Override
    public List<StoreFlowRefundDownloadVO> getStoreFlowRefundDownloadVO(Wrapper<StoreFlow> queryWrapper) {
        return baseMapper.getStoreFlowRefundDownloadVO(queryWrapper);
    }


    @Override
    public IPage<StoreFlow> getStoreFlow(String id, String type, PageVO pageVO) {
        Bill bill = billService.getById(id);
        return this.getStoreFlow(bill.getStoreId(), type, false, pageVO, bill.getStartTime(), bill.getCreateTime());
    }

    @Override
    public IPage<StoreFlow> getDistributionFlow(String id, PageVO pageVO) {
        Bill bill = billService.getById(id);
        return this.getStoreFlow(bill.getStoreId(), null, true, pageVO, bill.getStartTime(), bill.getCreateTime());
    }

}
package cn.lili.controller.promotion;

import cn.lili.common.enums.ResultUtil;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.vo.PageVO;
import cn.lili.common.vo.ResultMessage;
import cn.lili.modules.promotion.entity.dos.Seckill;
import cn.lili.modules.promotion.entity.dos.SeckillApply;
import cn.lili.modules.promotion.entity.enums.SeckillApplyStatusEnum;
import cn.lili.modules.promotion.entity.vos.SeckillSearchParams;
import cn.lili.modules.promotion.entity.vos.SeckillVO;
import cn.lili.modules.promotion.service.SeckillApplyService;
import cn.lili.modules.promotion.service.SeckillService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端,秒杀活动接口
 *
 * @author paulG
 * @date 2020/8/20
 **/
@RestController
@Api(tags = "管理端,秒杀活动接口")
@RequestMapping("/manager/promotion/seckill")
public class SeckillManagerController {
    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillApplyService seckillApplyService;

    @PostMapping
    @ApiOperation(value = "添加秒杀活动")
    public ResultMessage<Seckill> addSeckill(SeckillVO seckillVO) {
        AuthUser currentUser = UserContext.getCurrentUser();
        seckillVO.setStoreId(currentUser.getId());
        seckillVO.setStoreName(currentUser.getUsername());
        seckillVO.setSeckillApplyStatus(SeckillApplyStatusEnum.NOT_APPLY.name());
        seckillService.saveSeckill(seckillVO);
        return ResultUtil.data(seckillVO);
    }

    @PutMapping
    @ApiOperation(value = "修改秒杀活动")
    public ResultMessage<Seckill> updateSeckill(SeckillVO seckillVO) {
        AuthUser currentUser = UserContext.getCurrentUser();
        seckillVO.setStoreId(currentUser.getId());
        seckillVO.setStoreName(currentUser.getUsername());
        seckillService.modifySeckill(seckillVO);
        return ResultUtil.data(seckillVO);
    }

    @GetMapping(value = "/{id}")
    @ApiOperation(value = "通过id获取")
    public ResultMessage<Seckill> get(@PathVariable String id) {
        Seckill seckill = seckillService.getById(id);
        return ResultUtil.data(seckill);
    }

    @GetMapping
    @ApiOperation(value = "分页查询秒杀活动列表")
    public ResultMessage<IPage<SeckillVO>> getAll(SeckillSearchParams param, PageVO pageVo) {
        pageVo.setNotConvert(true);
        IPage<SeckillVO> page = seckillService.getSeckillByPageFromMongo(param, pageVo);
        return ResultUtil.data(page);
    }

    @DeleteMapping("/{id}")
    @ApiOperation(value = "删除一个秒杀活动")
    public ResultMessage<Object> deleteSeckill(@PathVariable String id) {
        seckillService.deleteSeckill(id);
        return ResultUtil.success();
    }

    @PutMapping("/close/{id}")
    @ApiOperation(value = "关闭一个秒杀活动")
    public ResultMessage<Object> closeSeckill(@PathVariable String id) {
        seckillService.closeSeckill(id);
        return ResultUtil.success();
    }

    @PutMapping("/open/{id}")
    @ApiOperation(value = "一个秒杀活动")
    public ResultMessage<Object> openSeckill(@PathVariable String id) {
        seckillService.openSeckill(id);
        return ResultUtil.success();
    }

    @GetMapping("/apply")
    @ApiOperation(value = "获取秒杀活动申请列表")
    public ResultMessage<IPage<SeckillApply>> getSeckillApply(SeckillSearchParams param, PageVO pageVo) {
        IPage<SeckillApply> seckillApply = seckillApplyService.getSeckillApplyFromMongo(param, pageVo);
        return ResultUtil.data(seckillApply);
    }

}

package cn.lili.modules.member.serviceimpl;


import cn.hutool.core.convert.Convert;
import cn.hutool.core.text.CharSequenceUtil;
import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.aop.annotation.DemoSite;
import cn.lili.common.context.ThreadContextHolder;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.enums.SwitchEnum;
import cn.lili.common.event.TransactionCommitSendMQEvent;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.token.Token;
import cn.lili.common.sensitive.SensitiveWordsFilter;
import cn.lili.common.utils.*;
import cn.lili.common.vo.PageVO;
import cn.lili.modules.connect.config.ConnectAuthEnum;
import cn.lili.modules.connect.entity.Connect;
import cn.lili.modules.connect.entity.dto.ConnectAuthUser;
import cn.lili.modules.connect.service.ConnectService;
import cn.lili.modules.member.aop.annotation.PointLogPoint;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.entity.dto.*;
import cn.lili.modules.member.entity.enums.PointTypeEnum;
import cn.lili.modules.member.entity.enums.QRCodeLoginSessionStatusEnum;
import cn.lili.modules.member.entity.vo.MemberSearchVO;
import cn.lili.modules.member.entity.vo.MemberVO;
import cn.lili.modules.member.entity.vo.QRCodeLoginSessionVo;
import cn.lili.modules.member.entity.vo.QRLoginResultVo;
import cn.lili.modules.member.mapper.MemberMapper;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.member.token.MemberTokenGenerate;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.entity.enums.StoreStatusEnum;
import cn.lili.modules.store.service.StoreService;
import cn.lili.mybatis.util.PageUtil;
import cn.lili.rocketmq.RocketmqSendCallbackBuilder;
import cn.lili.rocketmq.tags.MemberTagsEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * ???????????????????????????
 *
 * @author Chopper
 * @since 2021-03-29 14:10:16
 */
@Service
public class MemberServiceImpl extends ServiceImpl<MemberMapper, Member> implements MemberService {

    /**
     * ??????token
     */
    @Autowired
    private MemberTokenGenerate memberTokenGenerate;
    /**
     * ??????token
     */
    @Autowired
    private StoreTokenGenerate storeTokenGenerate;
    /**
     * ????????????
     */
    @Autowired
    private ConnectService connectService;
    /**
     * ??????
     */
    @Autowired
    private StoreService storeService;
    /**
     * RocketMQ ??????
     */
    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;
    /**
     * ??????
     */
    @Autowired
    private Cache cache;

    @Override
    public Member findByUsername(String userName) {
        QueryWrapper<Member> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("username", userName);
        return this.baseMapper.selectOne(queryWrapper);
    }


    @Override
    public Member getUserInfo() {
        AuthUser tokenUser = UserContext.getCurrentUser();
        if (tokenUser != null) {
            return this.findByUsername(tokenUser.getUsername());
        }
        throw new ServiceException(ResultCode.USER_NOT_LOGIN);
    }

    @Override
    public Member findByMobile(String mobile) {
        QueryWrapper<Member> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("mobile", mobile);
        return this.baseMapper.selectOne(queryWrapper);
    }

    @Override
    public boolean findByMobile(String uuid, String mobile) {
        QueryWrapper<Member> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("mobile", mobile);
        Member member = this.baseMapper.selectOne(queryWrapper);
        if (member == null) {
            throw new ServiceException(ResultCode.USER_NOT_PHONE);
        }
        cache.put(CachePrefix.FIND_MOBILE + uuid, mobile, 300L);

        return true;
    }

    @Override
    public Token usernameLogin(String username, String password) {
        Member member = this.findMember(username);
        //????????????????????????
        if (member == null || !member.getDisabled()) {
            throw new ServiceException(ResultCode.USER_NOT_EXIST);
        }
        //??????????????????????????????
        if (!new BCryptPasswordEncoder().matches(password, member.getPassword())) {
            throw new ServiceException(ResultCode.USER_PASSWORD_ERROR);
        }
        loginBindUser(member);
        return memberTokenGenerate.createToken(member, false);
    }


    @Override
    public void resetPassword(List<String> ids) {
        String password = new BCryptPasswordEncoder().encode(StringUtils.md5("123456"));
        LambdaUpdateWrapper<Member> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper.in(Member::getId, ids);
        lambdaUpdateWrapper.set(Member::getPassword, password);
        this.update(lambdaUpdateWrapper);
    }

    @Override
    public void updateHaveShop(Boolean haveStore, String storeId, List<String> memberIds) {
        List<Member> members = this.baseMapper.selectBatchIds(memberIds);
        if (members.size() > 0) {
            members.forEach(member -> {
                member.setHaveStore(haveStore);
                if (haveStore) {
                    member.setStoreId(storeId);
                } else {
                    member.setStoreId(null);
                }
            });
            this.updateBatchById(members);
        }
    }

    @Override
    public Token usernameStoreLogin(String username, String password) {

        Member member = this.findMember(username);
        //????????????????????????
        if (member == null || !member.getDisabled()) {
            throw new ServiceException(ResultCode.USER_NOT_EXIST);
        }
        //??????????????????????????????
        if (!new BCryptPasswordEncoder().matches(password, member.getPassword())) {
            throw new ServiceException(ResultCode.USER_PASSWORD_ERROR);
        }
        //??????????????????????????????
        if (Boolean.TRUE.equals(member.getHaveStore())) {
            Store store = storeService.getById(member.getStoreId());
            if (!store.getStoreDisable().equals(StoreStatusEnum.OPEN.name())) {
                throw new ServiceException(ResultCode.STORE_CLOSE_ERROR);
            }
        } else {
            throw new ServiceException(ResultCode.USER_NOT_EXIST);
        }

        return storeTokenGenerate.createToken(member, false);
    }

    /**
     * ??????????????????????????????
     *
     * @param userName ????????????????????????
     * @return ????????????
     */
    private Member findMember(String userName) {
        QueryWrapper<Member> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("username", userName).or().eq("mobile", userName);
        return this.getOne(queryWrapper);
    }

    @Override
    @Transactional
    public Token autoRegister(ConnectAuthUser authUser) {

        if (CharSequenceUtil.isEmpty(authUser.getNickname())) {
            authUser.setNickname("????????????");
        }
        if (CharSequenceUtil.isEmpty(authUser.getAvatar())) {
            authUser.setAvatar("https://i.loli.net/2020/11/19/LyN6JF7zZRskdIe.png");
        }
        try {
            String username = UuidUtils.getUUID();
            Member member = new Member(username, UuidUtils.getUUID(), authUser.getAvatar(), authUser.getNickname(),
                    authUser.getGender() != null ? Convert.toInt(authUser.getGender().getCode()) : 0);
            registerHandler(member);
            member.setPassword(DEFAULT_PASSWORD);
            //??????????????????
            loginBindUser(member, authUser.getUuid(), authUser.getSource());
            return memberTokenGenerate.createToken(member, false);
        } catch (ServiceException e) {
            log.error("?????????????????????????????????", e);
            throw e;
        } catch (Exception e) {
            log.error("?????????????????????", e);
            throw new ServiceException(ResultCode.USER_AUTO_REGISTER_ERROR);
        }
    }

    @Override
    @Transactional
    public Token autoRegister() {
        ConnectAuthUser connectAuthUser = this.checkConnectUser();
        return this.autoRegister(connectAuthUser);
    }

    @Override
    public Token refreshToken(String refreshToken) {
        return memberTokenGenerate.refreshToken(refreshToken);
    }

    @Override
    public Token refreshStoreToken(String refreshToken) {
        return storeTokenGenerate.refreshToken(refreshToken);
    }

    @Override
    @Transactional
    public Token mobilePhoneLogin(String mobilePhone) {
        QueryWrapper<Member> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("mobile", mobilePhone);
        Member member = this.baseMapper.selectOne(queryWrapper);
        //?????????????????????????????????????????????
        if (member == null) {
            member = new Member(mobilePhone, UuidUtils.getUUID(), mobilePhone);
            registerHandler(member);
        }
        loginBindUser(member);
        return memberTokenGenerate.createToken(member, false);
    }

    /**
     * ??????????????????
     *
     * @param member
     */
    @Transactional
    public void registerHandler(Member member) {
        member.setId(SnowFlake.getIdStr());
        //????????????
        this.save(member);


        // ????????????????????????
        applicationEventPublisher.publishEvent(new TransactionCommitSendMQEvent("new member register", rocketmqCustomProperties.getMemberTopic(), MemberTagsEnum.MEMBER_REGISTER.name(), member));
    }

    @Override
    public Member editOwn(MemberEditDTO memberEditDTO) {
        //??????????????????
        Member member = this.findByUsername(Objects.requireNonNull(UserContext.getCurrentUser()).getUsername());
        //????????????????????????
        BeanUtil.copyProperties(memberEditDTO, member);
        //????????????
        this.updateById(member);
        String destination = rocketmqCustomProperties.getMemberTopic() + ":" + MemberTagsEnum.MEMBER_INFO_EDIT.name();
        //??????????????????mq??????
        rocketMQTemplate.asyncSend(destination, member, RocketmqSendCallbackBuilder.commonCallback());
        return member;
    }

    @DemoSite
    public Member modifyPass(String oldPassword, String newPassword) {
        AuthUser tokenUser = UserContext.getCurrentUser();
        if (tokenUser == null) {
            throw new ServiceException(ResultCode.USER_NOT_LOGIN);
        }
        Member member = this.getById(tokenUser.getId());
        //?????????????????????????????????
        if (!new BCryptPasswordEncoder().matches(oldPassword, member.getPassword())) {
            throw new ServiceException(ResultCode.USER_OLD_PASSWORD_ERROR);
        }
        //??????????????????
        LambdaUpdateWrapper<Member> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper.eq(Member::getId, member.getId());
        lambdaUpdateWrapper.set(Member::getPassword, new BCryptPasswordEncoder().encode(newPassword));
        this.update(lambdaUpdateWrapper);
        return member;
    }

    @Override
    public boolean canInitPass() {
        AuthUser tokenUser = UserContext.getCurrentUser();
        if (tokenUser == null) {
            throw new ServiceException(ResultCode.USER_NOT_LOGIN);
        }
        Member member = this.getById(tokenUser.getId());
        return member.getPassword().equals(DEFAULT_PASSWORD);

    }

    @Override
    public void initPass(String password) {
        AuthUser tokenUser = UserContext.getCurrentUser();
        if (tokenUser == null) {
            throw new ServiceException(ResultCode.USER_NOT_LOGIN);
        }
        Member member = this.getById(tokenUser.getId());
        if (member.getPassword().equals(DEFAULT_PASSWORD)) {
            //??????????????????
            LambdaUpdateWrapper<Member> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
            lambdaUpdateWrapper.eq(Member::getId, member.getId());
            lambdaUpdateWrapper.set(Member::getPassword, new BCryptPasswordEncoder().encode(password));
            this.update(lambdaUpdateWrapper);
        }
        throw new ServiceException(ResultCode.UNINITIALIZED_PASSWORD);

    }

    @Override
    public void cancellation(String password) {

        AuthUser tokenUser = UserContext.getCurrentUser();
        if (tokenUser == null) {
            throw new ServiceException(ResultCode.USER_NOT_LOGIN);
        }
        Member member = this.getById(tokenUser.getId());
        if (member.getPassword().equals(new BCryptPasswordEncoder().encode(password))) {
            //??????????????????
            connectService.deleteByMemberId(member.getId());
            //??????????????????
            this.confusionMember(member);
        }
    }

    /**
     * ???????????????????????????
     *
     * @param member
     */
    private void confusionMember(Member member) {
        member.setUsername(UuidUtils.getUUID());
        member.setMobile(UuidUtils.getUUID() + member.getMobile());
        member.setNickName("???????????????");
        member.setDisabled(false);
        this.updateById(member);
    }

    @Override
    @Transactional
    public Token register(String userName, String password, String mobilePhone) {
        //??????????????????
        checkMember(userName, mobilePhone);
        //??????????????????
        Member member = new Member(userName, new BCryptPasswordEncoder().encode(password), mobilePhone);
        //?????????????????????????????????
        registerHandler(member);
        return memberTokenGenerate.createToken(member, false);
    }

    @Override
    public boolean changeMobile(String mobile) {
        AuthUser tokenUser = Objects.requireNonNull(UserContext.getCurrentUser());
        Member member = this.findByUsername(tokenUser.getUsername());

        //????????????????????????????????????ID?????????????????????ID
        if (!Objects.equals(tokenUser.getId(), member.getId())) {
            throw new ServiceException(ResultCode.USER_NOT_LOGIN);
        }
        //?????????????????????
        LambdaUpdateWrapper<Member> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper.eq(Member::getId, member.getId());
        lambdaUpdateWrapper.set(Member::getMobile, mobile);
        return this.update(lambdaUpdateWrapper);
    }

    @Override
    public boolean resetByMobile(String uuid, String password) {
        String phone = cache.get(CachePrefix.FIND_MOBILE + uuid).toString();
        //??????????????????????????????????????????????????????
        if (phone != null) {
            //????????????
            LambdaUpdateWrapper<Member> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
            lambdaUpdateWrapper.eq(Member::getMobile, phone);
            lambdaUpdateWrapper.set(Member::getPassword, new BCryptPasswordEncoder().encode(password));
            cache.remove(CachePrefix.FIND_MOBILE + uuid);
            return this.update(lambdaUpdateWrapper);
        } else {
            throw new ServiceException(ResultCode.USER_PHONE_NOT_EXIST);
        }

    }

    @Override
    @Transactional
    public Member addMember(MemberAddDTO memberAddDTO) {

        //??????????????????
        checkMember(memberAddDTO.getUsername(), memberAddDTO.getMobile());

        //????????????
        Member member = new Member(memberAddDTO.getUsername(), new BCryptPasswordEncoder().encode(memberAddDTO.getPassword()), memberAddDTO.getMobile());
        registerHandler(member);
        return member;
    }

    @Override
    public Member updateMember(ManagerMemberEditDTO managerMemberEditDTO) {
        //???????????????????????????
        if (CharSequenceUtil.isNotBlank(managerMemberEditDTO.getNickName())) {
            managerMemberEditDTO.setNickName(SensitiveWordsFilter.filter(managerMemberEditDTO.getNickName()));
        }
        //????????????????????????????????????
        if (CharSequenceUtil.isNotBlank(managerMemberEditDTO.getPassword())) {
            managerMemberEditDTO.setPassword(new BCryptPasswordEncoder().encode(managerMemberEditDTO.getPassword()));
        }
        //??????????????????
        Member member = this.getById(managerMemberEditDTO.getId());
        //????????????????????????
        BeanUtil.copyProperties(managerMemberEditDTO, member);
        this.updateById(member);
        return member;
    }

    @Override
    public IPage<MemberVO> getMemberPage(MemberSearchVO memberSearchVO, PageVO page) {
        QueryWrapper<Member> queryWrapper = Wrappers.query();
        //???????????????
        queryWrapper.like(CharSequenceUtil.isNotBlank(memberSearchVO.getUsername()), "username", memberSearchVO.getUsername());
        //???????????????
        queryWrapper.like(CharSequenceUtil.isNotBlank(memberSearchVO.getNickName()), "nick_name", memberSearchVO.getNickName());
        //????????????????????????
        queryWrapper.like(CharSequenceUtil.isNotBlank(memberSearchVO.getMobile()), "mobile", memberSearchVO.getMobile());
        //????????????????????????
        queryWrapper.eq(CharSequenceUtil.isNotBlank(memberSearchVO.getDisabled()), "disabled",
                memberSearchVO.getDisabled().equals(SwitchEnum.OPEN.name()) ? 1 : 0);
        queryWrapper.orderByDesc("create_time");
        return this.baseMapper.pageByMemberVO(PageUtil.initPage(page), queryWrapper);
    }

    @Override
    @PointLogPoint
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateMemberPoint(Long point, String type, String memberId, String content) {
        //????????????????????????
        Member member = this.getById(memberId);
        if (member != null) {
            //??????????????????????????????
            long currentPoint;
            //?????????????????????
            long totalPoint = member.getTotalPoint();
            //??????????????????
            if (type.equals(PointTypeEnum.INCREASE.name())) {
                currentPoint = member.getPoint() + point;
                //????????????????????? ???????????????????????????
                totalPoint = totalPoint + point;
            }
            //??????????????????
            else {
                currentPoint = member.getPoint() - point < 0 ? 0 : member.getPoint() - point;
            }
            member.setPoint(currentPoint);
            member.setTotalPoint(totalPoint);
            boolean result = this.updateById(member);
            if (result) {
                //??????????????????
                MemberPointMessage memberPointMessage = new MemberPointMessage();
                memberPointMessage.setPoint(point);
                memberPointMessage.setType(type);
                memberPointMessage.setMemberId(memberId);
                applicationEventPublisher.publishEvent(new TransactionCommitSendMQEvent("update member point", rocketmqCustomProperties.getMemberTopic(), MemberTagsEnum.MEMBER_POINT_CHANGE.name(), memberPointMessage));
                return true;
            }
            return false;

        }
        throw new ServiceException(ResultCode.USER_NOT_EXIST);
    }

    @Override
    public Boolean updateMemberStatus(List<String> memberIds, Boolean status) {
        UpdateWrapper<Member> updateWrapper = Wrappers.update();
        updateWrapper.set("disabled", status);
        updateWrapper.in("id", memberIds);

        return this.update(updateWrapper);
    }

    /**
     * ???????????????????????????
     *
     * @param mobilePhone ?????????
     * @return ??????
     */
    private Long findMember(String mobilePhone, String userName) {
        QueryWrapper<Member> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("mobile", mobilePhone)
                .or().eq("username", userName);
        return this.baseMapper.selectCount(queryWrapper);
    }

    /**
     * ??????cookie????????????????????????
     *
     * @param uuid uuid
     * @param type ??????
     * @return cookie????????????????????????
     */
    private ConnectAuthUser getConnectAuthUser(String uuid, String type) {
        Object context = cache.get(ConnectService.cacheKey(type, uuid));
        if (context != null) {
            return (ConnectAuthUser) context;
        }
        return null;
    }

    /**
     * ????????????????????????cookie?????????????????????????????????
     *
     * @param member  ??????
     * @param unionId unionId
     * @param type    ??????
     */
    private void loginBindUser(Member member, String unionId, String type) {
        Connect connect = connectService.queryConnect(
                ConnectQueryDTO.builder().unionId(unionId).unionType(type).build()
        );
        if (connect == null) {
            connect = new Connect(member.getId(), unionId, type);
            connectService.save(connect);
        }
    }

    /**
     * ????????????????????????cookie?????????????????????????????????
     *
     * @param member ??????
     */
    private void loginBindUser(Member member) {
        //??????cookie???????????????
        String uuid = CookieUtil.getCookie(ConnectService.CONNECT_COOKIE, ThreadContextHolder.getHttpRequest());
        String connectType = CookieUtil.getCookie(ConnectService.CONNECT_TYPE, ThreadContextHolder.getHttpRequest());
        //?????????????????????????????????
        if (CharSequenceUtil.isNotEmpty(uuid) && CharSequenceUtil.isNotEmpty(connectType)) {
            try {
                //????????????
                ConnectAuthUser connectAuthUser = getConnectAuthUser(uuid, connectType);
                if (connectAuthUser == null) {
                    return;
                }
                Connect connect = connectService.queryConnect(
                        ConnectQueryDTO.builder().unionId(connectAuthUser.getUuid()).unionType(connectType).build()
                );
                if (connect == null) {
                    connect = new Connect(member.getId(), connectAuthUser.getUuid(), connectType);
                    connectService.save(connect);
                }
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                log.error("????????????????????????????????????", e);
            } finally {
                //???????????????????????????????????????cookie????????????
                CookieUtil.delCookie(ConnectService.CONNECT_COOKIE, ThreadContextHolder.getHttpResponse());
                CookieUtil.delCookie(ConnectService.CONNECT_TYPE, ThreadContextHolder.getHttpResponse());
            }
        }

    }


    /**
     * ?????????????????????????????????????????????
     * ??????null??????
     * ????????????1???redis?????????????????????????????????  2????????????????????????
     *
     * @return ???????????????????????????????????????????????????????????????null?????????????????????????????????
     */
    private ConnectAuthUser checkConnectUser() {
        //??????cookie???????????????
        String uuid = CookieUtil.getCookie(ConnectService.CONNECT_COOKIE, ThreadContextHolder.getHttpRequest());
        String connectType = CookieUtil.getCookie(ConnectService.CONNECT_TYPE, ThreadContextHolder.getHttpRequest());

        //?????????????????????????????????
        if (CharSequenceUtil.isNotEmpty(uuid) && CharSequenceUtil.isNotEmpty(connectType)) {
            //?????? ????????????????????????
            ConnectAuthEnum authInterface = ConnectAuthEnum.valueOf(connectType);

            ConnectAuthUser connectAuthUser = getConnectAuthUser(uuid, connectType);
            if (connectAuthUser == null) {
                throw new ServiceException(ResultCode.USER_OVERDUE_CONNECT_ERROR);
            }
            //?????????????????????????????????
            Connect connect = connectService.queryConnect(
                    ConnectQueryDTO.builder().unionType(connectType).unionId(connectAuthUser.getUuid()).build()
            );
            //?????????????????????true???????????????????????????
            if (connect == null) {
                connectAuthUser.setConnectEnum(authInterface);
                return connectAuthUser;
            } else {
                throw new ServiceException(ResultCode.USER_CONNECT_BANDING_ERROR);
            }
        } else {
            throw new ServiceException(ResultCode.USER_CONNECT_NOT_EXIST_ERROR);
        }
    }

    @Override
    public long getMemberNum(MemberSearchVO memberSearchVO) {
        QueryWrapper<Member> queryWrapper = Wrappers.query();
        //???????????????
        queryWrapper.like(CharSequenceUtil.isNotBlank(memberSearchVO.getUsername()), "username", memberSearchVO.getUsername());
        //????????????????????????
        queryWrapper.like(CharSequenceUtil.isNotBlank(memberSearchVO.getMobile()), "mobile", memberSearchVO.getMobile());
        //??????????????????
        queryWrapper.eq(CharSequenceUtil.isNotBlank(memberSearchVO.getDisabled()), "disabled",
                memberSearchVO.getDisabled().equals(SwitchEnum.OPEN.name()) ? 1 : 0);
        queryWrapper.orderByDesc("create_time");
        return this.count(queryWrapper);
    }

    /**
     * ????????????????????????
     *
     * @param columns   ??????????????????
     * @param memberIds ??????ids
     * @return ??????????????????
     */
    @Override
    public List<Map<String, Object>> listFieldsByMemberIds(String columns, List<String> memberIds) {
        return this.listMaps(new QueryWrapper<Member>()
                .select(columns)
                .in(memberIds != null && !memberIds.isEmpty(), "id", memberIds));
    }

    /**
     * ??????
     */
    @Override
    public void logout(UserEnums userEnums) {
        String currentUserToken = UserContext.getCurrentUserToken();
        if (CharSequenceUtil.isNotEmpty(currentUserToken)) {
            cache.remove(CachePrefix.ACCESS_TOKEN.getPrefix(userEnums) + currentUserToken);
        }
    }

    /**
     * ??????????????????????????????
     *
     * @return ????????????????????????
     */
    @Override
    public List<String> getAllMemberMobile() {
        return this.baseMapper.getAllMemberMobile();
    }

    /**
     * ???????????????????????????????????????
     *
     * @param memberId ??????id
     * @return ??????????????????
     */
    @Override
    public boolean updateMemberLoginTime(String memberId) {
        LambdaUpdateWrapper<Member> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Member::getId, memberId);
        updateWrapper.set(Member::getLastLoginDate, new Date());
        return this.update(updateWrapper);
    }

    @Override
    public MemberVO getMember(String id) {
        return new MemberVO(this.getById(id));
    }

    @Override
    public QRCodeLoginSessionVo createPcSession() {
        QRCodeLoginSessionVo session = new QRCodeLoginSessionVo();
        session.setStatus(QRCodeLoginSessionStatusEnum.WAIT_SCANNING.getCode());
        //???????????????20s
        Long duration = 20 * 1000L;
        session.setDuration(duration);
        String token = CachePrefix.QR_CODE_LOGIN_SESSION.name() + SnowFlake.getIdStr();
        session.setToken(token);
        cache.put(token, session, duration, TimeUnit.MILLISECONDS);
        return session;
    }

    @Override
    public Object appScanner(String token) {
        AuthUser tokenUser = UserContext.getCurrentUser();
        if (tokenUser == null) {
            throw new ServiceException(ResultCode.USER_NOT_LOGIN);
        }
        QRCodeLoginSessionVo session = (QRCodeLoginSessionVo) cache.get(token);
        if (session == null) {
            return QRCodeLoginSessionStatusEnum.NO_EXIST.getCode();
        }
        session.setStatus(QRCodeLoginSessionStatusEnum.SCANNING.getCode());
        cache.put(token, session, session.getDuration(), TimeUnit.MILLISECONDS);
        return QRCodeLoginSessionStatusEnum.SCANNING.getCode();
    }

    @Override
    public boolean appSConfirm(String token, Integer code) {
        AuthUser tokenUser = UserContext.getCurrentUser();
        if (tokenUser == null) {
            throw new ServiceException(ResultCode.USER_NOT_LOGIN);
        }
        QRCodeLoginSessionVo session = (QRCodeLoginSessionVo) cache.get(token);
        if (session == null) {
            return false;
        }
        if (code == 1) {
            //??????
            session.setStatus(QRCodeLoginSessionStatusEnum.VERIFIED.getCode());
            session.setUserId(Long.parseLong(tokenUser.getId()));
        } else {
            //??????
            session.setStatus(QRCodeLoginSessionStatusEnum.CANCELED.getCode());
        }
        cache.put(token, session, session.getDuration(), TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public QRLoginResultVo loginWithSession(String sessionToken) {
        QRLoginResultVo result = new QRLoginResultVo();
        result.setStatus(QRCodeLoginSessionStatusEnum.NO_EXIST.getCode());
        QRCodeLoginSessionVo session = (QRCodeLoginSessionVo) cache.get(sessionToken);
        if (session == null) {
            return result;
        }
        result.setStatus(session.getStatus());
        if (QRCodeLoginSessionStatusEnum.VERIFIED.getCode().equals(session.getStatus())) {
            //??????token
            Member member = this.getById(session.getUserId());
            if (member == null) {
                throw new ServiceException(ResultCode.USER_NOT_EXIST);
            } else {
                //??????token
                Token token = memberTokenGenerate.createToken(member, false);
                result.setToken(token);
                cache.vagueDel(sessionToken);
            }

        }
        return result;
    }

    /**
     * ????????????
     *
     * @param userName    ????????????
     * @param mobilePhone ?????????
     */
    private void checkMember(String userName, String mobilePhone) {
        //???????????????????????????
        if (findMember(mobilePhone, userName) > 0) {
            throw new ServiceException(ResultCode.USER_EXIST);
        }
    }
}
package cn.lili.modules.connect.serviceimpl;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.lili.cache.Cache;
import cn.lili.common.enums.ClientTypeEnum;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.token.Token;
import cn.lili.common.utils.HttpUtils;
import cn.lili.common.utils.UuidUtils;
import cn.lili.modules.connect.entity.Connect;
import cn.lili.modules.connect.entity.dto.AuthToken;
import cn.lili.modules.connect.entity.dto.ConnectAuthUser;
import cn.lili.modules.connect.entity.dto.MemberConnectLoginMessage;
import cn.lili.modules.connect.entity.dto.WechatMPLoginParams;
import cn.lili.modules.connect.entity.enums.ConnectEnum;
import cn.lili.modules.connect.entity.enums.SourceEnum;
import cn.lili.modules.connect.mapper.ConnectMapper;
import cn.lili.modules.connect.service.ConnectService;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.entity.dto.ConnectQueryDTO;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.member.token.MemberTokenGenerate;
import cn.lili.modules.system.entity.dos.Setting;
import cn.lili.modules.system.entity.dto.connect.WechatConnectSetting;
import cn.lili.modules.system.entity.dto.connect.dto.WechatConnectSettingItem;
import cn.lili.modules.system.entity.enums.SettingEnum;
import cn.lili.modules.system.service.SettingService;
import cn.lili.rocketmq.RocketmqSendCallbackBuilder;
import cn.lili.rocketmq.tags.MemberTagsEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.naming.NoPermissionException;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.Security;
import java.util.*;

/**
 * 联合登陆接口实现
 *
 * @author Chopper
 */
@Slf4j
@Service
public class ConnectServiceImpl extends ServiceImpl<ConnectMapper, Connect> implements ConnectService {

    static final boolean AUTO_REGION = true;

    @Autowired
    private SettingService settingService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private MemberTokenGenerate memberTokenGenerate;
    @Autowired
    private Cache cache;
    /**
     * RocketMQ
     */
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    /**
     * RocketMQ配置
     */
    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Token unionLoginCallback(ConnectAuthUser authUser, String uuid) {
        return this.unionLoginCallback(authUser, false);
    }

    @Override
    public void bind(String unionId, String type) {
        AuthUser authUser = Objects.requireNonNull(UserContext.getCurrentUser());
        Connect connect = new Connect(authUser.getId(), unionId, type);
        this.save(connect);


    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbind(String type) {

        LambdaQueryWrapper<Connect> queryWrapper = new LambdaQueryWrapper<>();

        queryWrapper.eq(Connect::getUserId, UserContext.getCurrentUser().getId());
        queryWrapper.eq(Connect::getUnionType, type);

        this.remove(queryWrapper);
    }

    @Override
    public List<String> bindList() {
        LambdaQueryWrapper<Connect> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Connect::getUserId, UserContext.getCurrentUser().getId());
        List<Connect> connects = this.list(queryWrapper);
        List<String> keys = new ArrayList<>();
        connects.forEach(item -> keys.add(item.getUnionType()));
        return keys;
    }


    @Override
    @Transactional
    public Token miniProgramAutoLogin(WechatMPLoginParams params) {

        Map<String, String> map = new HashMap<>(3);
        //得到微信小程序联合登陆信息
        JSONObject json = this.getConnect(params.getCode());
        //存储session key 后续登录用得到
        String sessionKey = json.getStr("session_key");
        String unionId = json.getStr("unionid");
        String openId = json.getStr("openid");
        map.put("sessionKey", sessionKey);
        map.put("unionId", unionId);
        map.put("openId", openId);

        //微信联合登陆参数
        return phoneMpBindAndLogin(map.get("sessionKey"), params, map.get("openId"), map.get("unionId"));
    }

    /**
     * 通过微信返回等code 获取openid 等信息
     *
     * @param code 微信code
     * @return 微信返回的信息
     */
    public JSONObject getConnect(String code) {
        WechatConnectSettingItem setting = getWechatMPSetting();
        String url = "https://api.weixin.qq.com/sns/jscode2session?" +
                "appid=" + setting.getAppId() + "&" +
                "secret=" + setting.getAppSecret() + "&" +
                "js_code=" + code + "&" +
                "grant_type=authorization_code";
        String content = HttpUtils.doGet(url, "UTF-8", 100, 1000);
        log.error(content);
        return JSONUtil.parseObj(content);
    }

    /**
     * 手机号 绑定 且 自动登录
     *
     * @param sessionKey 微信sessionKey
     * @param params     微信小程序自动登录参数
     * @param openId     微信openid
     * @param unionId    微信unionid
     * @return token
     */
    @Transactional(rollbackFor = Exception.class)
    public Token phoneMpBindAndLogin(String sessionKey, WechatMPLoginParams params, String openId, String unionId) {
        try {
            String encryptedData = params.getEncryptedData();
            String iv = params.getIv();
            JSONObject userInfo = this.getUserInfo(encryptedData, sessionKey, iv);
            log.info("联合登陆返回：{}", userInfo.toString());


            ConnectAuthUser connectAuthUser = new ConnectAuthUser();
            connectAuthUser.setUuid(openId);
            connectAuthUser.setNickname(params.getNickName());
            connectAuthUser.setAvatar(params.getImage());

            if (userInfo.containsKey("purePhoneNumber")) {
                String phone = (String) userInfo.get("purePhoneNumber");
                connectAuthUser.setUsername("m" + phone);
                connectAuthUser.setPhone(phone);
            } else {
                connectAuthUser.setUsername(UuidUtils.getUUID());
            }
            connectAuthUser.setSource(ConnectEnum.WECHAT);
            connectAuthUser.setType(ClientTypeEnum.WECHAT_MP);

            AuthToken authToken = new AuthToken();
            authToken.setUnionId(unionId);
            connectAuthUser.setToken(authToken);
            return this.unionLoginCallback(connectAuthUser, true);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public Connect queryConnect(ConnectQueryDTO connectQueryDTO) {

        LambdaQueryWrapper<Connect> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CharSequenceUtil.isNotEmpty(connectQueryDTO.getUserId()), Connect::getUserId,
                        connectQueryDTO.getUserId())
                .eq(CharSequenceUtil.isNotEmpty(connectQueryDTO.getUnionType()), Connect::getUnionType,
                        connectQueryDTO.getUnionType())
                .eq(CharSequenceUtil.isNotEmpty(connectQueryDTO.getUnionId()), Connect::getUnionId,
                        connectQueryDTO.getUnionId());
        return this.getOne(queryWrapper, false);
    }

    @Override
    public void deleteByMemberId(String userId) {
        LambdaQueryWrapper<Connect> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Connect::getUserId, userId);
        this.remove(queryWrapper);
    }

    /**
     * 成功登录，则检测cookie中的信息，进行会员绑定
     *
     * @param userId  用户ID
     * @param unionId 第三方用户ID
     * @param type    类型
     */
    @Override
    public void loginBindUser(String userId, String unionId, String type) {
        Connect connect = this.queryConnect(
                ConnectQueryDTO.builder().unionId(unionId).unionType(type).build()
        );
        //如果未绑定则直接绑定
        if (connect == null) {
            connect = new Connect(userId, unionId, type);
            this.save(connect);
            //如果已绑定不是当前用户信息则删除绑定信息，重新绑定
        } else if (!connect.getUserId().equals(userId)) {
            this.removeById(connect.getId());
            this.loginBindUser(userId, unionId, type);
        }
    }


    /**
     * 第三方联合登陆
     * 1.判断是否使用开放平台
     * 1.1如果使用开放平台则使用UnionId进行登录
     * 1.2如果不适用开放平台则使用OpenId进行登录
     * <p>
     * 2.用户登录后判断绑定OpenId
     *
     * @param authUser 第三方登录封装类
     * @param longTerm 是否长时间有效
     * @return token
     * @throws NoPermissionException 不允许操作
     */
    private Token unionLoginCallback(ConnectAuthUser authUser, boolean longTerm) {

        try {
            Member member = null;
            //判断是否传递手机号，如果传递手机号则使用手机号登录
            if (StrUtil.isNotBlank(authUser.getPhone())) {
                member = memberService.findByMobile(authUser.getPhone());
            }
            //如果未查到手机号的会员则使用第三方登录
            if (member == null) {
                LambdaQueryWrapper<Connect> queryWrapper = new LambdaQueryWrapper<Connect>();
                //使用UnionId登录
                if (authUser.getToken() != null && StrUtil.isNotBlank(authUser.getToken().getUnionId())) {
                    queryWrapper.eq(Connect::getUnionId, authUser.getToken().getUnionId())
                            .eq(Connect::getUnionType, authUser.getSource());
                } else {
                    //使用OpenID登录
                    SourceEnum sourceEnum = SourceEnum.getSourceEnum(authUser.getSource(), authUser.getType());
                    queryWrapper.eq(Connect::getUnionId, authUser.getUuid())
                            .eq(Connect::getUnionType, sourceEnum.name());
                }

                //查询绑定关系
                Connect connect = this.getOne(queryWrapper);

                if (connect == null) {
                    member = memberService.autoRegister(authUser);
                } else {
                    //查询会员
                    member = memberService.getById(connect.getUserId());
                    //如果未绑定会员，则把刚才查询到的联合登录表数据删除
                    if (member == null) {
                        this.remove(queryWrapper);
                        member = memberService.autoRegister(authUser);
                    }
                }
            }

            //发送用户第三方登录消息
            MemberConnectLoginMessage memberConnectLoginMessage = new MemberConnectLoginMessage();
            memberConnectLoginMessage.setMember(member);
            memberConnectLoginMessage.setConnectAuthUser(authUser);
            String destination =
                    rocketmqCustomProperties.getMemberTopic() + ":" + MemberTagsEnum.MEMBER_CONNECT_LOGIN.name();
            //发送用户第三方登录消息
            rocketMQTemplate.asyncSend(destination, JSONUtil.toJsonStr(memberConnectLoginMessage),
                    RocketmqSendCallbackBuilder.commonCallback());

            return memberTokenGenerate.createToken(member, longTerm);
        } catch (Exception e) {
            log.error("联合登陆失败：", e);
            throw e;
        }
    }

    /**
     * 获取微信小程序配置
     *
     * @return 微信小程序配置
     */
    private WechatConnectSettingItem getWechatMPSetting() {
        Setting setting = settingService.get(SettingEnum.WECHAT_CONNECT.name());

        WechatConnectSetting wechatConnectSetting = JSONUtil.toBean(setting.getSettingValue(),
                WechatConnectSetting.class);

        if (wechatConnectSetting == null) {
            throw new ServiceException(ResultCode.WECHAT_CONNECT_NOT_EXIST);
        }
        //寻找对应对微信小程序登录配置
        for (WechatConnectSettingItem wechatConnectSettingItem : wechatConnectSetting.getWechatConnectSettingItems()) {
            if (wechatConnectSettingItem.getClientType().equals(ClientTypeEnum.WECHAT_MP.name())) {
                return wechatConnectSettingItem;
            }
        }

        throw new ServiceException(ResultCode.WECHAT_CONNECT_NOT_EXIST);
    }


    /**
     * 解密，获取微信信息
     *
     * @param encryptedData 加密信息
     * @param sessionKey    微信sessionKey
     * @param iv            微信揭秘参数
     * @return 用户信息
     */
    public JSONObject getUserInfo(String encryptedData, String sessionKey, String iv) {

        log.info("encryptedData:{},sessionKey:{},iv:{}", encryptedData, sessionKey, iv);
        //被加密的数据
        byte[] dataByte = Base64.getDecoder().decode(encryptedData);
        //加密秘钥
        byte[] keyByte = Base64.getDecoder().decode(sessionKey);
        //偏移量
        byte[] ivByte = Base64.getDecoder().decode(iv);
        try {
            //如果密钥不足16位，那么就补足.  这个if 中的内容很重要
            int base = 16;
            if (keyByte.length % base != 0) {
                int groups = keyByte.length / base + (keyByte.length % base != 0 ? 1 : 0);
                byte[] temp = new byte[groups * base];
                Arrays.fill(temp, (byte) 0);
                System.arraycopy(keyByte, 0, temp, 0, keyByte.length);
                keyByte = temp;
            }
            //初始化
            Security.addProvider(new BouncyCastleProvider());
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            SecretKeySpec spec = new SecretKeySpec(keyByte, "AES");
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("AES");
            parameters.init(new IvParameterSpec(ivByte));
            //初始化
            cipher.init(Cipher.DECRYPT_MODE, spec, parameters);
            byte[] resultByte = cipher.doFinal(dataByte);
            if (null != resultByte && resultByte.length > 0) {
                String result = new String(resultByte, StandardCharsets.UTF_8);
                return JSONUtil.parseObj(result);
            }
        } catch (Exception e) {
            log.error("解密，获取微信信息错误", e);
        }
        throw new ServiceException(ResultCode.USER_CONNECT_ERROR);
    }


}
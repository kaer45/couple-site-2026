package com.couple.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.couple.anniversary.entity.Anniversary;
import com.couple.anniversary.mapper.AnniversaryMapper;
import com.couple.auth.dto.BindRequest;
import com.couple.auth.dto.BindResponseVO;
import com.couple.auth.dto.CoupleInfoVO;
import com.couple.auth.dto.LoginRequest;
import com.couple.auth.dto.LoginResponse;
import com.couple.auth.dto.MeVO;
import com.couple.auth.dto.PartnerVO;
import com.couple.auth.dto.RegisterRequest;
import com.couple.auth.dto.UserVO;
import com.couple.common.BusinessException;
import com.couple.couple.entity.Couple;
import com.couple.couple.mapper.CoupleMapper;
import com.couple.file.FileStorageService;
import com.couple.user.entity.User;
import com.couple.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 认证服务实现
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 情侣码字符集：大写字母+数字，剔除易混淆字符 O/0/I/1 */
    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 6;

    private final UserMapper userMapper;
    private final CoupleMapper coupleMapper;
    private final AnniversaryMapper anniversaryMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final FileStorageService fileStorageService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse register(RegisterRequest request) {
        // 用户名查重
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (count != null && count > 0) {
            throw new BusinessException(400, "用户名已被注册");
        }

        // 生成唯一情侣码
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setCoupleCode(generateUniqueCoupleCode());
        userMapper.insert(user);

        String token = jwtUtil.generateToken(user.getId(), user.getCoupleId());
        return new LoginResponse(token, UserVO.from(user), user.getCoupleCode());
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(400, "用户名或密码错误");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getCoupleId());
        return new LoginResponse(token, UserVO.from(user), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BindResponseVO bind(Long currentUserId, BindRequest request) {
        User currentUser = userMapper.selectById(currentUserId);
        if (currentUser == null) {
            throw new BusinessException(401, "未登录或Token已过期");
        }
        // 当前用户已绑定
        if (currentUser.getCoupleId() != null) {
            throw new BusinessException(400, "当前账号已绑定");
        }

        // 校验情侣码
        String code = request.getCoupleCode().trim().toUpperCase();
        User codeOwner = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getCoupleCode, code));
        if (codeOwner == null) {
            throw new BusinessException(400, "情侣码不存在");
        }
        // 不能绑定自己的情侣码（否则会创建 user_a=user_b=自己的关系）
        if (codeOwner.getId().equals(currentUserId)) {
            throw new BusinessException(400, "不能绑定自己的情侣码");
        }
        if (codeOwner.getCoupleId() != null) {
            throw new BusinessException(400, "该情侣码已被使用");
        }

        // startDate 缺省为当天
        LocalDate startDate = request.getStartDate() != null ? request.getStartDate() : LocalDate.now();

        // 1. 创建 couple 关系（码主人为 user_a，当前用户为 user_b）
        Couple couple = new Couple();
        couple.setUserAId(codeOwner.getId());
        couple.setUserBId(currentUserId);
        couple.setStartDate(startDate);
        coupleMapper.insert(couple);

        // 2. 更新双方 couple_id，并作废情侣码
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, codeOwner.getId())
                .set(User::getCoupleId, couple.getId())
                .set(User::getCoupleCode, null));
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, currentUserId)
                .set(User::getCoupleId, couple.getId())
                .set(User::getCoupleCode, null));

        // 3. 自动创建"在一起的那天"纪念日（is_start=1，不可删除）
        Anniversary anniversary = new Anniversary();
        anniversary.setCoupleId(couple.getId());
        anniversary.setName("在一起的那天");
        anniversary.setDate(startDate);
        anniversary.setIsStart(true);
        anniversary.setRemindDays(0);
        anniversaryMapper.insert(anniversary);

        PartnerVO partner = new PartnerVO(codeOwner.getId(), codeOwner.getNickname(), codeOwner.getAvatar());
        return new BindResponseVO(couple.getId(), startDate, partner);
    }

    @Override
    public MeVO me(Long currentUserId) {
        User user = userMapper.selectById(currentUserId);
        if (user == null) {
            throw new BusinessException(401, "未登录或Token已过期");
        }

        PartnerVO partner = null;
        CoupleInfoVO coupleInfo = null;

        if (user.getCoupleId() != null) {
            Couple couple = coupleMapper.selectById(user.getCoupleId());
            if (couple != null) {
                Long partnerId = couple.getUserAId().equals(user.getId())
                        ? couple.getUserBId() : couple.getUserAId();
                User partnerUser = userMapper.selectById(partnerId);
                if (partnerUser != null) {
                    partner = new PartnerVO(partnerUser.getId(), partnerUser.getNickname(), partnerUser.getAvatar());
                }
                long daysTogether = ChronoUnit.DAYS.between(couple.getStartDate(), LocalDate.now());
                coupleInfo = new CoupleInfoVO(couple.getId(), couple.getStartDate(), daysTogether);
            }
        }
        return new MeVO(UserVO.from(user), partner, coupleInfo);
    }

    @Override
    public UserVO updateAvatar(Long userId, String avatarUrl) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(401, "未登录或Token已过期");
        }
        // 如果原来的头像存在，那么删除头像
        if(user.getAvatar()!=null){
            fileStorageService.delete(user.getAvatar());
        }
        user.setAvatar(avatarUrl);
        userMapper.updateById(user);
        return UserVO.from(user);
    }

    /** 生成 6 位唯一情侣码（大写字母+数字） */
    private String generateUniqueCoupleCode() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int j = 0; j < CODE_LENGTH; j++) {
                sb.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]);
            }
            String code = sb.toString();
            Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getCoupleCode, code));
            if (count == null || count == 0) {
                return code;
            }
        }
        throw new BusinessException(500, "情侣码生成失败，请重试");
    }
}

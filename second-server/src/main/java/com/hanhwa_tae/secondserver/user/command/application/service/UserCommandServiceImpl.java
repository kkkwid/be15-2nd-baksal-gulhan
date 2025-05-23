package com.hanhwa_tae.secondserver.user.command.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanhwa_tae.secondserver.auth.command.domain.aggregate.model.CustomUserDetail;
import com.hanhwa_tae.secondserver.common.domain.DeleteType;
import com.hanhwa_tae.secondserver.common.exception.BusinessException;
import com.hanhwa_tae.secondserver.common.exception.ErrorCode;
import com.hanhwa_tae.secondserver.user.command.application.dto.UserCreateDTO;
import com.hanhwa_tae.secondserver.user.command.application.dto.UserInfoCreateDTO;
import com.hanhwa_tae.secondserver.user.command.application.dto.request.*;
import com.hanhwa_tae.secondserver.user.command.domain.aggregate.*;
import com.hanhwa_tae.secondserver.user.command.domain.repository.UserInfoRepository;
import com.hanhwa_tae.secondserver.delivery.command.domain.repository.DeliveryAddressRepository;
import com.hanhwa_tae.secondserver.user.command.domain.repository.UserRepository;
import com.hanhwa_tae.secondserver.user.command.infrastructure.RedisUserIdRepository;
import com.hanhwa_tae.secondserver.user.command.infrastructure.RedisUserPasswordRepository;
import com.hanhwa_tae.secondserver.user.command.infrastructure.RedisUserRepository;
import com.hanhwa_tae.secondserver.user.query.mapper.UserMapper;
import com.hanhwa_tae.secondserver.utils.EmailUtil;
import com.hanhwa_tae.secondserver.utils.RandomStringGenerator;
import jakarta.mail.MessagingException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserCommandServiceImpl implements UserCommandService {

    private final UserRepository userRepository;
    private final UserInfoRepository userInfoRepository;
    private final ModelMapper modelMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;    // 필요 없을지도..?
    private final EmailUtil emailUtil;
    private final ObjectMapper objectMapper;
    private final RedisUserRepository redisUserRepository;
    private final RandomStringGenerator randomStringGenerator;
    private final RedisUserIdRepository redisUserIdRepository;
    private final DeliveryAddressRepository deliveryAddressRepository;
    private final RedisUserPasswordRepository redisUserPasswordRepository;


    //    @Transactional
    public void registerUser(@Valid UserCreateRequest request) throws MessagingException {
        User duplicateIdUser = userMapper.findUserByUserId(request.getUserId()).orElse(null);
        User duplicateEmailUser = userMapper.findUserByEmail(request.getEmail()).orElse(null);

        log.info(String.valueOf(request.getIsAgreed()));

        if(!request.getIsAgreed()){
            throw new BusinessException(ErrorCode.UNAUTHORIZED_REQUEST);
        }

        // 중복 유저가 존재할 경우
        if (duplicateIdUser != null) {
            throw new BusinessException(ErrorCode.DUPLICATE_ID_EXISTS);
        }

        // 중복 이메일이 존재할 경우
        if (duplicateEmailUser != null) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL_EXISTS);
        }

        // 비밀번호가 일치하지 않을 경우
        if(!request.getPassword().equals(request.getConfirmPassword())){
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        }

        // 1. Redis에 데이터 저장
        String uuid = UUID.randomUUID().toString();

        request.setEncodedPassword(passwordEncoder.encode(request.getPassword()));

        // 1.2. redis에 유저 정보 저장
        try {
            String userData = objectMapper.writeValueAsString(request);
            RedisUser user = RedisUser.builder()
                    .uuid(uuid)
                    .userData(userData)
                    .build();
            redisUserRepository.save(user);

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        // 2. 이메일 보내기
        String verifyUrl = "http://localhost:8000/api/v1/s2/users/verify-email?uuid=" + uuid;

        StringBuilder sb = new StringBuilder();
        sb.append("<h1>이메일 인증</h1>");
        sb.append("<p>아래 버튼을 클릭하면 이메일 인증이 완료됩니다.</p>");
        sb.append("<a href=\"").append(verifyUrl).append("\" style=\"display: inline-block; padding: 12px 24px; background-color: #4CAF50; color: white; text-decoration: none; border-radius: 6px; font-size: 16px;\">이메일 인증하기</a>");


        emailUtil.sendEmail(request.getEmail(), "[걸한] 이메일 인증 입니다.", sb.toString());


        /* 이 아래는 verify 된 유저가 수행해야할 로직임 */
        // 평민 등급 조회
    }

    @Override
    @Transactional
    public boolean verifyByEmail(String uuid) {
        try {
            // 1. redis에서 uuid로 데이터 조회
            RedisUser userData = redisUserRepository.findById(uuid).orElseThrow(
                    () -> new BusinessException(ErrorCode.EMAIL_CODE_EXPIRED)
            );

            // 2. redis에서 삭제
            redisUserRepository.deleteById(uuid);

            // 3. JSON 문자열 → 객체 변환
            UserCreateRequest userRequestDto = objectMapper.readValue(userData.getUserData(), UserCreateRequest.class);

            // 4. 중복 가입 여부 확인
            User duplicateUser = userMapper.findUserByUserId(userRequestDto.getUserId()).orElse(null);
            if (duplicateUser != null) {
                return false; // 이미 가입된 경우 false
            }

            // 5. 유저 생성
            Rank defaultRank = userMapper.findRankIdByRankName(RankType.COMMONER.name());

            UserCreateDTO userDto = UserCreateDTO.builder()
                    .userId(userRequestDto.getUserId())
                    .password(userRequestDto.getPassword())
                    .username(userRequestDto.getUsername())
                    .email(userRequestDto.getEmail())
                    .gender(userRequestDto.getGender())
                    .rankId((long) defaultRank.getRankId())
                    .loginType(LoginType.GENERAL)
                    .build();

            User user = modelMapper.map(userDto, User.class);
            user.setDefaultRank(defaultRank);
            userRepository.save(user);

            // 6. 유저 상세 정보 저장
            UserInfoCreateDTO userInfoDto = UserInfoCreateDTO.builder()
                    .user(user)
                    .birth(userRequestDto.getBirth())
                    .phone(userRequestDto.getPhone())
                    .address(userRequestDto.getAddress() + " " + userRequestDto.getDetailAddress())
                    .countryCode(userRequestDto.getCountryCode())
                    .build();

            UserInfo userInfo = modelMapper.map(userInfoDto, UserInfo.class);
            userInfoRepository.save(userInfo);

            return true;
        } catch (Exception e) {
            // 로그 기록 (선택)
            log.warn("이메일 인증 중 오류 발생: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    @Transactional
    public void updateUserInfo(CustomUserDetail userDetail, UpdateUserInfoRequest request) {
        if (userDetail == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        String userId = userDetail.getUserId();

        User user = userRepository.findUserByUserId(userId).orElseThrow(
                () -> new BusinessException(ErrorCode.USER_NOT_FOUND)
        );

        String rawPassword = request.getPassword();

        if (rawPassword != null && !rawPassword.isBlank()) {
            String newPassword = passwordEncoder.encode(rawPassword);
            user.setUpdateUser(newPassword);
        }

        userDetail.getAuthorities().forEach(v ->
                log.info(v.toString()));

        boolean isSlave = userDetail.getAuthorities()
                .contains(new SimpleGrantedAuthority("SLAVE"));

        log.info("관리자 여부 : " + isSlave);
        if (!isSlave) {
            UserInfo userInfo = userInfoRepository.findByUserNo(user.getUserNo());
            userInfo.setUpdateUserInfo(request.getAddress(), request.getPhone());
            userInfoRepository.save(userInfo);
        }
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void findUserPassword(UserFindPasswordRequest request) throws MessagingException {
        String requestUserId = request.getUserId();
        String requestEmail = request.getEmail();


        // 1. 유저 존재 확인
        User user = userRepository.findUserByUserIdAndEmail(requestUserId, requestEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 2. 해당 유저의 이메일에 인증 요청을 보냄
        StringBuilder sb = new StringBuilder();

        String uuid = UUID.randomUUID().toString();

        sb.append("<h1>이메일 인증<h1>");
        sb.append("<h2>인증 코드 : ").append(uuid).append("<h2>");

        emailUtil.sendEmail(request.getEmail(), "[걸한] 이메일 인증 입니다.", sb.toString());

        RedisUserPassword redisData = RedisUserPassword.builder().uuid(uuid).build();

        redisUserPasswordRepository.save(redisData);
    }

    @Override
    public void verifyFindPassword(UserVerifyFindPasswordRequest request) throws MessagingException {
        String requestUserId = request.getUserId();
        String requestEmail = request.getEmail();
        String uuid = request.getUuid();


        // 1. 유저 존재 확인
        User user = userRepository.findUserByUserIdAndEmail(requestUserId, requestEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if(!redisUserPasswordRepository.existsById(uuid)){
            throw new BusinessException(ErrorCode.EMAIL_AUTHORIZATION_ERROR);
        }

        // 2. 해당 유저의 이메일로 임시 비밀번호 전송
        StringBuilder sb = new StringBuilder();

        String tempPassword = randomStringGenerator.getRandomString(15);

        sb.append("<h1>임시 비밀번호<h1>");
        sb.append("<h2>임시 비밀번호 : ").append(tempPassword).append("<h2>");

        emailUtil.sendEmail(request.getEmail(), "[걸한] 임시 비밀번호 입니다.", sb.toString());

        user.setUpdateUser(passwordEncoder.encode(tempPassword));
        userRepository.save(user);
    }

    @Override
    public void findUserId(UserFindIdRequest request) throws MessagingException {

        String requestEmail = request.getEmail();

        // 1. DB에 해당 이메일을 가진 유저가 존재하는지 확인
        User user = userMapper.findUserByEmail(requestEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 2. 해당 유저의 이메일에 인증 요청을 보냄
        StringBuilder sb = new StringBuilder();

        String uuid = UUID.randomUUID().toString();

        sb.append("<h1>이메일 인증<h1>");
        sb.append("<h2>인증 코드 : ").append(uuid).append("<h2>");

        emailUtil.sendEmail(request.getEmail(), "[걸한] 이메일 인증 입니다.", sb.toString());

        // 3. redis에 인증번호 : ID 형태로 저장
        RedisUserId redisUserId = RedisUserId
                .builder()
                .uuid(uuid)
                .userId(user.getUserId())
                .build();

        redisUserIdRepository.save(redisUserId);
        // 4. 인증 완료 시 : ID를 부분 마스킹해서 내뱉어줌
    }

    @Override
    public String verifyFindUserId(String uuid) {
        RedisUserId redisUserId = redisUserIdRepository.findById(uuid).orElseThrow(
                () -> new BusinessException(ErrorCode.EMAIL_CODE_EXPIRED)
        );

        String userId = redisUserId.getUserId();
        redisUserIdRepository.delete(redisUserId);

        int maskingStartIdx = (int) Math.ceil(userId.length() * 0.3);

        String realValue = userId.substring(0, maskingStartIdx);
        String maskingValue = "*".repeat(userId.length() - maskingStartIdx);

        return realValue + maskingValue;
    }

    @Override
    public void changeUserPassword(CustomUserDetail userDetail, ChangeUserPasswordRequest request) {
        Long userNo = userDetail.getUserNo();

        String requestPassword = request.getPassword();
        String requestConfirmPassword = request.getConfirmPassword();

        /* 비밀번호 확인과 동일한 경우 에러 출력 */
        if (!requestPassword.equals(requestConfirmPassword)) {
            throw new BusinessException(ErrorCode.PASSWORD_CONFIRM_FAILED);
        }


        User foundUser = userRepository.findUserByUserNo(userNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 비밀번호가 동일한 경우
        if (passwordEncoder.matches(requestPassword, foundUser.getPassword())) {
            throw new BusinessException(ErrorCode.SAME_PASSWORD);
        }

        // 패스워드 인코딩
        String encodedPassword = passwordEncoder.encode(requestPassword);
        foundUser.setUpdateUser(encodedPassword);

        userRepository.save(foundUser);
    }

    @Override
    @Transactional
    public void withdrawUser(CustomUserDetail userDetail) {
        Long userNo = userDetail.getUserNo();

        User user = userRepository.findUserByUserNo(userNo).orElseThrow(
                () -> new BusinessException(ErrorCode.USER_NOT_FOUND)
        );

        if (user.getIsDeleted().equals(DeleteType.Y)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        user.setWithdrawUser();

        userRepository.save(user);
    }

}

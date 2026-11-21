<script lang="ts" setup>
definePage({
  style: {
    navigationBarTitleText: '关于',
  },
})

/**
 * 版本号取运行时应用信息，不 import manifest.json。
 *
 * <p>manifest.json 是构建生成物，编译成 require 后没有 default 导出，
 * 默认导入会得到 undefined 并让页面崩溃（连带主包注册失败），因此改用官方 API。
 */
const version = (() => {
  try {
    const info = uni.getAccountInfoSync() as { miniProgram?: { version?: string } }
    const v = info?.miniProgram?.version
    return v && v !== 'devtools' ? v : '开发版'
  }
  catch {
    return '—'
  }
})()

const features = [
  { title: '培训任务', desc: '企业下发任务，按课程与章节推进学习' },
  { title: '学习记录', desc: '服务端计时，进度只进不退' },
  { title: '在线考试', desc: '多次考试保留全部成绩，取最佳判定合格' },
  { title: '培训档案', desc: '累计学时与考试流水随时可查' },
]

function openPrivacy() {
  uni.navigateTo({ url: '/pages/me/privacy' })
}
</script>

<template>
  <view class="page">
    <view class="brand">
      <view class="brand-mark">
        训
      </view>
      <view class="brand-name">
        企业内训平台
      </view>
      <view class="brand-version">
        版本 {{ version }}
      </view>
    </view>

    <view class="section">
      <view class="section-title">
        平台能做什么
      </view>
      <view
        v-for="item in features"
        :key="item.title"
        class="feature"
      >
        <view class="feature-dot" />
        <view class="feature-body">
          <view class="feature-title">
            {{ item.title }}
          </view>
          <view class="feature-desc">
            {{ item.desc }}
          </view>
        </view>
      </view>
    </view>

    <view class="section">
      <view class="section-title">
        数据与隐私
      </view>
      <view class="paragraph">
        手机号由服务端调用微信接口获取，客户端提交的手机号不作为身份凭证。学习时长与考试成绩均由服务端判定与留存。
      </view>
      <view class="link-row" @click="openPrivacy">
        <text class="link-text">查看隐私协议</text>
        <text class="link-arrow">›</text>
      </view>
    </view>

    <view class="footer">
      企业内训平台 · 通用企业培训与考核
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  background-color: #f4f6f9;
}

.brand {
  padding: 48rpx 32rpx;
  border-radius: 24rpx;
  background: linear-gradient(145deg, #0e5a8a 0%, #12405f 60%, #0b2c42 100%);
  text-align: center;
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 104rpx;
  height: 104rpx;
  margin: 0 auto;
  border-radius: 28rpx;
  background: rgba(255, 255, 255, 0.16);
  color: #ffffff;
  font-size: 46rpx;
  font-weight: 700;
}

.brand-name {
  margin-top: 22rpx;
  font-size: 34rpx;
  font-weight: 600;
  color: #ffffff;
}

.brand-version {
  margin-top: 10rpx;
  font-size: 23rpx;
  color: rgba(255, 255, 255, 0.66);
}

.section {
  margin-top: 24rpx;
  padding: 28rpx;
  border: 1rpx solid #e2e8ef;
  border-radius: 20rpx;
  background-color: #ffffff;
}

.section-title {
  font-size: 27rpx;
  font-weight: 600;
  color: #16202b;
}

.feature {
  display: flex;
  gap: 16rpx;
  margin-top: 22rpx;
}

.feature-dot {
  flex-shrink: 0;
  width: 12rpx;
  height: 12rpx;
  margin-top: 10rpx;
  border-radius: 50%;
  background-color: #0e5a8a;
}

.feature-body {
  flex: 1;
}

.feature-title {
  font-size: 26rpx;
  font-weight: 500;
  color: #16202b;
}

.feature-desc {
  margin-top: 6rpx;
  font-size: 22rpx;
  line-height: 1.6;
  color: #8a97a5;
}

.paragraph {
  margin-top: 18rpx;
  font-size: 24rpx;
  line-height: 1.8;
  color: #5b6b7c;
}

.link-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 24rpx;
  padding: 22rpx 24rpx;
  border-radius: 14rpx;
  background-color: #eef3f8;
}

.link-text {
  font-size: 26rpx;
  font-weight: 500;
  color: #0e5a8a;
}

.link-arrow {
  color: #0e5a8a;
  font-size: 34rpx;
  line-height: 1;
}

.footer {
  margin-top: 36rpx;
  text-align: center;
  font-size: 21rpx;
  color: #a4b0bd;
}
</style>

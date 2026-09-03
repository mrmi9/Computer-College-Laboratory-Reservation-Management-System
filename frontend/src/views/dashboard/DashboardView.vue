<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace('/login')
}
</script>

<template>
  <main class="page-shell">
    <header class="page-header">
      <div>
        <p class="eyebrow">
          计算机学院
        </p>
        <h1>实验室预约管理系统</h1>
      </div>
      <div class="user-actions">
        <div class="user-summary">
          <strong>{{ auth.user?.realName }}</strong>
          <span>{{ auth.user?.username }}</span>
        </div>
        <el-button @click="logout">
          退出登录
        </el-button>
      </div>
    </header>
    <section class="content-panel">
      <h2>欢迎使用</h2>
      <p>当前账号角色：{{ auth.user?.roles.join('、') }}</p>
    </section>
  </main>
</template>

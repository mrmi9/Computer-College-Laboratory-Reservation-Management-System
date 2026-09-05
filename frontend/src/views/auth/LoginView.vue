<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiErrorMessage } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')
const passwordChanged = computed(() => route.query.passwordChanged === '1')

async function submit(): Promise<void> {
  if (submitting.value) return
  errorMessage.value = ''
  submitting.value = true
  try {
    const user = await auth.login(form.username.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(user.mustChangePassword ? '/change-password' : redirect)
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '登录失败，请检查账号和密码。')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section
      class="auth-panel"
      aria-labelledby="login-title"
    >
      <p class="eyebrow">
        计算机学院
      </p>
      <h1 id="login-title">
        实验室预约
      </h1>
      <p class="supporting">
        统一查询空闲课次、提交申请并跟踪审批状态。
      </p>
      <el-alert
        v-if="passwordChanged"
        title="密码已修改，请使用新密码登录。"
        type="success"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
      />
      <el-form
        class="stacked-form"
        :model="form"
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item
          label="学工号"
          required
        >
          <el-input
            v-model="form.username"
            autocomplete="username"
          />
        </el-form-item>
        <el-form-item
          label="密码"
          required
        >
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            show-password
          />
        </el-form-item>
        <el-button
          class="primary-action"
          type="primary"
          native-type="submit"
          :loading="submitting"
        >
          登录
        </el-button>
      </el-form>
    </section>
  </main>
</template>

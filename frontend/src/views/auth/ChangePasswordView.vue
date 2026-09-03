<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { apiErrorMessage } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const submitting = ref(false)
const errorMessage = ref('')
const mismatch = computed(
  () => form.confirmPassword.length > 0 && form.newPassword !== form.confirmPassword,
)

async function submit(): Promise<void> {
  if (submitting.value || mismatch.value) return
  errorMessage.value = ''
  submitting.value = true
  try {
    await auth.changePassword(form.currentPassword, form.newPassword)
    await router.replace({ path: '/login', query: { passwordChanged: '1' } })
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '密码修改失败，请稍后重试。')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section
      class="auth-panel"
      aria-labelledby="password-title"
    >
      <p class="eyebrow">
        首次登录安全设置
      </p>
      <h1 id="password-title">
        修改初始密码
      </h1>
      <p class="supporting">
        新密码至少 12 位，并同时包含大写字母、小写字母、数字和特殊字符。
      </p>
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
          label="当前密码"
          required
        >
          <el-input
            v-model="form.currentPassword"
            type="password"
            autocomplete="current-password"
            show-password
          />
        </el-form-item>
        <el-form-item
          label="新密码"
          required
        >
          <el-input
            v-model="form.newPassword"
            type="password"
            autocomplete="new-password"
            minlength="12"
            show-password
          />
        </el-form-item>
        <el-form-item
          label="确认新密码"
          :error="mismatch ? '两次输入的密码不一致' : ''"
          required
        >
          <el-input
            v-model="form.confirmPassword"
            type="password"
            autocomplete="new-password"
            minlength="12"
            show-password
          />
        </el-form-item>
        <el-button
          class="primary-action"
          type="primary"
          native-type="submit"
          :loading="submitting"
          :disabled="mismatch"
        >
          保存并重新登录
        </el-button>
      </el-form>
    </section>
  </main>
</template>

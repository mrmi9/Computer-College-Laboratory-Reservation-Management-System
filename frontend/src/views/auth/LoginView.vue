<script setup lang="ts">
import { reactive } from 'vue'

const form = reactive({ username: '', password: '' })
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
      <el-form
        :model="form"
        label-position="top"
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
        >
          登录
        </el-button>
      </el-form>
    </section>
  </main>
</template>

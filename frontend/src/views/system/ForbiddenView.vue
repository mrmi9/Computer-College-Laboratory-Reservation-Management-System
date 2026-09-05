<template>
  <el-result
    icon="warning"
    title="无权访问"
    sub-title="当前账号没有访问此功能的权限。"
  >
    <template #extra>
      <RouterLink to="/dashboard">
        <el-button type="primary">
          返回首页
        </el-button>
      </RouterLink>
    </template>
  </el-result>
</template>

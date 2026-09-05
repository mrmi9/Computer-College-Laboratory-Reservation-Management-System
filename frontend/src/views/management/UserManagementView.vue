<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { apiErrorMessage } from '@/api/errors'
import { createRole, createUser, getPermissions, getRoles, getSettings, getUsers, importUsers, replaceUserRoles, updateRole, updateSetting, updateUser } from '@/api/administration'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { Permission, RoleAdmin, Setting, UserAdmin } from '@/types/business'

const tab = ref('users'); const users = ref<UserAdmin[]>([]); const roles = ref<RoleAdmin[]>([]); const permissions = ref<Permission[]>([]); const settings = ref<Setting[]>([]); const loading = ref(true); const error = ref('')
const userDialog = ref(false); const roleDialog = ref(false); const assignmentDialog = ref(false); const editingUser = ref<UserAdmin | null>(null); const editingRole = ref<RoleAdmin | null>(null); const selectedUser = ref<UserAdmin | null>(null); const assignedRoles = ref<string[]>([]); const importResult = ref('')
const userForm = reactive({ username: '', realName: '', userType: 'STUDENT', department: '', email: '', phone: '', status: 'ACTIVE', roles: ['STUDENT'] as string[], initialPassword: '' })
const roleForm = reactive({ code: '', name: '', description: '', permissions: [] as string[] })

async function load(): Promise<void> { loading.value = true; error.value = ''; try { const [userData, roleData, permissionData, settingData] = await Promise.all([getUsers({ size: 100 }), getRoles(), getPermissions(), getSettings()]); users.value = userData.items; roles.value = roleData; permissions.value = permissionData; settings.value = settingData } catch (caught) { error.value = apiErrorMessage(caught, '系统管理数据加载失败。') } finally { loading.value = false } }
function openUser(item?: UserAdmin): void { editingUser.value = item ?? null; Object.assign(userForm, item === undefined ? { username: '', realName: '', userType: 'STUDENT', department: '', email: '', phone: '', status: 'ACTIVE', roles: ['STUDENT'], initialPassword: '' } : { username: item.username, realName: item.realName, userType: item.userType, department: item.department ?? '', email: item.email ?? '', phone: item.phone ?? '', status: item.status, roles: [...item.roles], initialPassword: '' }); userDialog.value = true }
async function saveUser(): Promise<void> { try { if (editingUser.value === null) await createUser(userForm); else await updateUser(editingUser.value.id, { username: userForm.username, realName: userForm.realName, userType: userForm.userType, department: userForm.department, email: userForm.email, phone: userForm.phone, status: userForm.status, version: editingUser.value.version }); ElMessage.success('用户已保存'); userDialog.value = false; await load() } catch (caught) { ElMessage.error(apiErrorMessage(caught, '用户保存失败。')) } }
function openAssignment(item: UserAdmin): void { selectedUser.value = item; assignedRoles.value = [...item.roles]; assignmentDialog.value = true }
async function saveAssignment(): Promise<void> { if (selectedUser.value === null) return; try { await replaceUserRoles(selectedUser.value.id, assignedRoles.value, selectedUser.value.version); ElMessage.success('角色已更新，用户旧会话已撤销'); assignmentDialog.value = false; await load() } catch (caught) { ElMessage.error(apiErrorMessage(caught, '角色分配失败。')); await load() } }
function openRole(item?: RoleAdmin): void { editingRole.value = item ?? null; Object.assign(roleForm, item === undefined ? { code: '', name: '', description: '', permissions: [] } : { code: item.code, name: item.name, description: item.description ?? '', permissions: [...item.permissions] }); roleDialog.value = true }
async function saveRole(): Promise<void> { try { if (editingRole.value === null) await createRole(roleForm); else await updateRole(editingRole.value.id, { name: roleForm.name, description: roleForm.description, permissions: roleForm.permissions, version: editingRole.value.version }); ElMessage.success('角色已保存，相关旧会话已撤销'); roleDialog.value = false; await load() } catch (caught) { ElMessage.error(apiErrorMessage(caught, '角色保存失败。')); await load() } }
async function saveSetting(item: Setting): Promise<void> { try { const updated = await updateSetting(item.key, item.value, item.description, item.version); Object.assign(item, updated); ElMessage.success('参数已保存') } catch (caught) { ElMessage.error(apiErrorMessage(caught, '参数保存失败。')); await load() } }
async function chooseImport(event: Event): Promise<void> { const input = event.target as HTMLInputElement; const file = input.files?.[0]; if (file === undefined) return; importResult.value = ''; try { const result = await importUsers(file); importResult.value = `任务 ${result.id}：成功 ${result.succeededRows} 行，失败 ${result.failedRows} 行${result.errorSummary ? `；${result.errorSummary}` : ''}`; await load() } catch (caught) { importResult.value = apiErrorMessage(caught, '导入失败。') } finally { input.value = '' } }
onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <header class="page-heading">
      <div>
        <p class="eyebrow">
          系统管理
        </p><h1>用户、角色与参数</h1><p>安全相关变更会立即撤销受影响用户的旧会话并写入审计。</p>
      </div>
    </header><PageState
      :loading="loading"
      :error="error"
      @retry="load"
    >
      <section class="panel">
        <el-tabs v-model="tab">
          <el-tab-pane
            label="用户"
            name="users"
          >
            <div class="section-heading">
              <p>共 {{ users.length }} 个用户</p><el-button
                type="primary"
                @click="openUser()"
              >
                新增用户
              </el-button>
            </div><div class="responsive-table">
              <el-table :data="users">
                <el-table-column
                  label="账号"
                  min-width="170"
                >
                  <template #default="scope">
                    <strong>{{ scope.row.realName }}</strong><br><small>{{ scope.row.username }} · {{ scope.row.department ?? '未填写部门' }}</small>
                  </template>
                </el-table-column><el-table-column
                  label="身份与角色"
                  min-width="220"
                >
                  <template #default="scope">
                    <span>{{ scope.row.userType }}</span><div class="tag-list compact">
                      <el-tag
                        v-for="role in scope.row.roles"
                        :key="role"
                        effect="plain"
                      >
                        {{ role }}
                      </el-tag>
                    </div>
                  </template>
                </el-table-column><el-table-column
                  label="状态"
                  width="100"
                >
                  <template #default="scope">
                    <StatusTag :status="scope.row.status" />
                  </template>
                </el-table-column><el-table-column
                  prop="noShowCount"
                  label="爽约"
                  width="70"
                /><el-table-column
                  label="操作"
                  width="150"
                >
                  <template #default="scope">
                    <el-button
                      text
                      @click="openUser(scope.row)"
                    >
                      编辑
                    </el-button><el-button
                      text
                      @click="openAssignment(scope.row)"
                    >
                      角色
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-tab-pane>
          <el-tab-pane
            label="角色与权限"
            name="roles"
          >
            <div class="section-heading">
              <p>内置角色受安全约束保护</p><el-button
                type="primary"
                @click="openRole()"
              >
                新增角色
              </el-button>
            </div><div class="role-grid">
              <article
                v-for="role in roles"
                :key="role.id"
                class="subtle-card"
              >
                <div class="section-heading">
                  <div><strong>{{ role.name }}</strong><small>{{ role.code }} · {{ role.builtIn ? '内置' : '自定义' }}</small></div><el-button
                    text
                    @click="openRole(role)"
                  >
                    编辑
                  </el-button>
                </div><p>{{ role.description ?? '暂无说明' }}</p><div class="tag-list">
                  <el-tag
                    v-for="permission in role.permissions"
                    :key="permission"
                    effect="plain"
                  >
                    {{ permission }}
                  </el-tag>
                </div>
              </article>
            </div>
          </el-tab-pane>
          <el-tab-pane
            label="系统参数"
            name="settings"
          >
            <div class="settings-list">
              <article
                v-for="item in settings"
                :key="item.key"
                class="setting-row"
              >
                <span><strong>{{ item.key }}</strong><small>{{ item.description }}</small></span><el-switch
                  v-if="typeof item.value === 'boolean'"
                  v-model="item.value"
                /><el-input-number
                  v-else-if="typeof item.value === 'number'"
                  v-model="item.value"
                  :min="0"
                /><el-input
                  v-else
                  v-model="item.value"
                /><el-button @click="saveSetting(item)">
                  保存
                </el-button>
              </article>
            </div>
          </el-tab-pane>
          <el-tab-pane
            label="批量导入"
            name="import"
          >
            <div class="import-panel">
              <h2>CSV 批量导入用户</h2><p>UTF-8 文件，表头依次为 username、realName、userType、department、email、phone、roles、initialPassword；多个角色用竖线分隔。</p><label class="file-button">选择 CSV 文件<input
                type="file"
                accept=".csv,text/csv"
                @change="chooseImport"
              ></label><el-alert
                v-if="importResult"
                :title="importResult"
                :type="importResult.startsWith('任务') ? 'success' : 'error'"
                :closable="false"
              />
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>
    </PageState>

    <el-dialog
      v-model="userDialog"
      :title="editingUser === null ? '新增用户' : '编辑用户'"
      width="min(680px, 94vw)"
    >
      <el-form
        :model="userForm"
        label-position="top"
        @submit.prevent="saveUser"
      >
        <div class="form-grid">
          <el-form-item
            label="用户名"
            required
          >
            <el-input v-model="userForm.username" />
          </el-form-item><el-form-item
            label="姓名"
            required
          >
            <el-input v-model="userForm.realName" />
          </el-form-item><el-form-item label="用户类型">
            <el-select v-model="userForm.userType">
              <el-option
                label="学生"
                value="STUDENT"
              /><el-option
                label="教师"
                value="TEACHER"
              /><el-option
                label="职工"
                value="STAFF"
              />
            </el-select>
          </el-form-item><el-form-item label="状态">
            <el-select v-model="userForm.status">
              <el-option
                label="启用"
                value="ACTIVE"
              /><el-option
                label="停用"
                value="DISABLED"
              /><el-option
                label="锁定"
                value="LOCKED"
              />
            </el-select>
          </el-form-item><el-form-item label="部门">
            <el-input v-model="userForm.department" />
          </el-form-item><el-form-item label="手机号">
            <el-input v-model="userForm.phone" />
          </el-form-item><el-form-item label="邮箱">
            <el-input v-model="userForm.email" />
          </el-form-item><el-form-item
            v-if="editingUser === null"
            label="角色"
            required
          >
            <el-select
              v-model="userForm.roles"
              multiple
            >
              <el-option
                v-for="role in roles"
                :key="role.code"
                :label="role.name"
                :value="role.code"
              />
            </el-select>
          </el-form-item><el-form-item
            v-if="editingUser === null"
            label="初始密码"
            required
          >
            <el-input
              v-model="userForm.initialPassword"
              type="password"
              show-password
              minlength="12"
            />
          </el-form-item>
        </div><div class="dialog-actions">
          <el-button @click="userDialog = false">
            取消
          </el-button><el-button
            type="primary"
            native-type="submit"
          >
            保存
          </el-button>
        </div>
      </el-form>
    </el-dialog>
    <el-dialog
      v-model="assignmentDialog"
      :title="`分配角色 · ${selectedUser?.realName ?? ''}`"
      width="min(520px, 94vw)"
    >
      <el-checkbox-group
        v-model="assignedRoles"
        class="checkbox-stack"
      >
        <el-checkbox
          v-for="role in roles"
          :key="role.code"
          :value="role.code"
        >
          {{ role.name }}（{{ role.code }}）
        </el-checkbox>
      </el-checkbox-group><div class="dialog-actions">
        <el-button @click="assignmentDialog = false">
          取消
        </el-button><el-button
          type="primary"
          @click="saveAssignment"
        >
          保存并撤销旧会话
        </el-button>
      </div>
    </el-dialog>
    <el-dialog
      v-model="roleDialog"
      :title="editingRole === null ? '新增角色' : '编辑角色'"
      width="min(700px, 94vw)"
    >
      <el-form
        :model="roleForm"
        label-position="top"
        @submit.prevent="saveRole"
      >
        <div class="form-grid">
          <el-form-item
            label="角色编码"
            required
          >
            <el-input
              v-model="roleForm.code"
              :disabled="editingRole !== null"
            />
          </el-form-item><el-form-item
            label="名称"
            required
          >
            <el-input v-model="roleForm.name" />
          </el-form-item>
        </div><el-form-item label="说明">
          <el-input v-model="roleForm.description" />
        </el-form-item><el-form-item label="权限">
          <el-checkbox-group
            v-model="roleForm.permissions"
            class="permission-grid"
          >
            <el-checkbox
              v-for="permission in permissions"
              :key="permission.code"
              :value="permission.code"
            >
              {{ permission.name }}<small>{{ permission.code }}</small>
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item><div class="dialog-actions">
          <el-button @click="roleDialog = false">
            取消
          </el-button><el-button
            type="primary"
            native-type="submit"
          >
            保存
          </el-button>
        </div>
      </el-form>
    </el-dialog>
  </section>
</template>

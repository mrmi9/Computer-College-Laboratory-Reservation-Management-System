import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import LoginView from '@/views/auth/LoginView.vue'

describe('LoginView', () => {
  it('renders accessible account fields', () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: LoginView }],
    })
    const wrapper = mount(LoginView, {
      global: {
        plugins: [createPinia(), router],
        stubs: {
          ElForm: { template: '<form><slot /></form>' },
          ElFormItem: { template: '<label><slot /></label>' },
          ElInput: { template: '<input />' },
          ElButton: { template: '<button><slot /></button>' },
          ElAlert: { template: '<div><slot /></div>' },
        },
      },
    })
    expect(wrapper.get('h1').text()).toBe('实验室预约')
    expect(wrapper.findAll('input')).toHaveLength(2)
    expect(wrapper.get('button').text()).toBe('登录')
  })
})

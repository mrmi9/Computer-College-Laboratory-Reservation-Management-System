import { mount } from '@vue/test-utils'
import LoginView from '@/views/auth/LoginView.vue'

describe('LoginView', () => {
  it('renders accessible account fields', () => {
    const wrapper = mount(LoginView, {
      global: {
        stubs: {
          ElForm: { template: '<form><slot /></form>' },
          ElFormItem: { template: '<label><slot /></label>' },
          ElInput: { template: '<input />' },
          ElButton: { template: '<button><slot /></button>' },
        },
      },
    })
    expect(wrapper.get('h1').text()).toBe('实验室预约')
    expect(wrapper.findAll('input')).toHaveLength(2)
    expect(wrapper.get('button').text()).toBe('登录')
  })
})

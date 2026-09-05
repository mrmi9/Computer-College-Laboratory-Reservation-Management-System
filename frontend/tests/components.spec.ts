import { mount } from '@vue/test-utils'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'

describe('shared state components', () => {
  it('renders a textual status marker in addition to color', () => {
    const wrapper = mount(StatusTag, {
      props: { status: 'APPROVED' },
      global: { stubs: { ElTag: { template: '<span class="tag"><slot /></span>' } } },
    })
    expect(wrapper.text()).toContain('通')
    expect(wrapper.text()).toContain('已通过')
  })

  it('prioritizes errors and emits retry', async () => {
    const wrapper = mount(PageState, {
      props: { error: '网络暂时不可用', empty: true },
      slots: { default: '<p>业务内容</p>' },
      global: {
        stubs: {
          ElResult: { template: '<section><slot name="extra" /></section>' },
          ElButton: { template: '<button><slot /></button>' },
          ElEmpty: { template: '<div>空状态</div>' },
          ElSkeleton: { template: '<div>加载中</div>' },
        },
      },
    })
    expect(wrapper.text()).toContain('重新加载')
    expect(wrapper.text()).not.toContain('业务内容')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('shows business content only in the ready state', () => {
    const wrapper = mount(PageState, { slots: { default: '<p>业务内容</p>' } })
    expect(wrapper.text()).toBe('业务内容')
  })
})

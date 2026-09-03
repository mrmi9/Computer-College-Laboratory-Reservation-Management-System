import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/main.css'
import { setAuthExpiredHandler } from './api/http'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia).use(router).use(ElementPlus)

setAuthExpiredHandler(() => {
  useAuthStore(pinia).clear()
  void router.replace('/login')
})

app.mount('#app')

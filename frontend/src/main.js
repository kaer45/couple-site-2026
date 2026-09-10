import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import relativeTime from 'dayjs/plugin/relativeTime'

import 'element-plus/dist/index.css'
import './styles/index.css'

import App from './App.vue'
import router from './router'

// dayjs：使用中文 locale，并启用相对时间插件（fromNow 显示 "3 天前"）
dayjs.locale('zh-cn')
dayjs.extend(relativeTime)

const app = createApp(App)

app.use(createPinia())
app.use(router)
// Element Plus 全量引入 + 中文语言包
app.use(ElementPlus, { locale: zhCn })

// 全局注册所有图标组件，模板中可直接使用 <House />、<Calendar /> 等
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

app.mount('#app')

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.scss'
import App from './App.vue'
import router from './router'
import {
  clearStaleChunkRecoveryMarker,
  registerStaleChunkRecovery,
} from '@/utils/staleChunkRecovery'

registerStaleChunkRecovery()
createApp(App).use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')

void router.isReady()
  .then(() => clearStaleChunkRecoveryMarker())
  .catch(() => undefined)

import { ref, watch } from 'vue'

const THEME_KEY = 'streetlight-theme'
const current = ref(localStorage.getItem(THEME_KEY) || 'dark')

function apply(name) {
  document.documentElement.setAttribute('data-theme', name)
  localStorage.setItem(THEME_KEY, name)
  current.value = name
}

// 初始化
apply(current.value)

// 监听系统偏好
if (window.matchMedia) {
  window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', (e) => {
    // 仅在没有手动设置过时跟随系统
    if (!localStorage.getItem(THEME_KEY)) {
      apply(e.matches ? 'light' : 'dark')
    }
  })
}

export function useTheme() {
  function toggle() {
    apply(current.value === 'dark' ? 'light' : 'dark')
  }

  const isDark = ref(current.value === 'dark')
  watch(current, (v) => { isDark.value = v === 'dark' })

  return { current, isDark, toggle }
}

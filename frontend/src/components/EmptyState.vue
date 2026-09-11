<template>
  <!-- 通用空状态组件：emoji 或 el-icon 图标 + 描述 + 插槽（可放操作按钮）
       传入 iconComponent（如 'Camera'/'Picture'/'Bell'）时渲染 Element Plus 图标，
       否则回退到 icon 字符串（emoji） -->
  <el-empty :description="description" class="empty-state">
    <template #image>
      <el-icon v-if="iconComponent" class="empty-icon"><component :is="iconComponent" /></el-icon>
      <div v-else class="empty-icon">{{ icon }}</div>
    </template>
    <slot />
  </el-empty>
</template>

<script setup>
defineProps({
  description: { type: String, default: '这里还空空如也～' },
  icon: { type: String, default: '🌸' },
  iconComponent: { type: String, default: '' }
})
</script>

<style scoped>
.empty-state {
  padding: 40px 0;
}

.empty-icon {
  font-size: 64px;
  line-height: 1;
}
</style>
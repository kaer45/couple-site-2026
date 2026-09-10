<template>
  <!-- 通用空状态组件：emoji 图标 + 描述 + 插槽（可放操作按钮） -->
  <el-empty :description="description" class="empty-state">
    <template #image>
      <div class="empty-icon">{{ icon }}</div>
    </template>
    <slot />
  </el-empty>
</template>

<script setup>
defineProps({
  description: { type: String, default: '这里还空空如也～' },
  icon: { type: String, default: '🌸' }
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

<script setup lang="ts">
import { ref } from 'vue'

import WmsStructure from './components/WmsStructure.vue'
import FlowEditor from './views/FlowEditor.vue'

type Mode = 'mindmap' | 'flow'

const mode = ref<Mode>('flow')
</script>

<template>
  <div class="app">
    <div class="mode-switch">
      <button type="button" :class="{ active: mode === 'mindmap' }" @click="mode = 'mindmap'">
        思维导图
      </button>
      <button type="button" :class="{ active: mode === 'flow' }" @click="mode = 'flow'">
        流程图
      </button>
    </div>

    <!-- v-if 而非 v-show：LogicFlow 取景依赖容器的可见尺寸，
         隐藏状态下容器为 0 会把图缩到看不见 -->
    <WmsStructure v-if="mode === 'mindmap'" />
    <FlowEditor v-else />
  </div>
</template>

<style scoped>
.app {
  width: 100%;
  height: 100%;
}

.mode-switch {
  position: fixed;
  top: 20px;
  left: 20px;
  z-index: 999;
  display: flex;
  gap: 6px;
  background: #ffffff;
  padding: 4px;
  border: 1px solid #e4e9f0;
  border-radius: 6px;
}

.mode-switch button {
  padding: 6px 14px;
  font-size: 13px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #4b5563;
  cursor: pointer;
}

.mode-switch button.active {
  background: #1677ff;
  color: #ffffff;
}
</style>
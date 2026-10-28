<!--
 * @Author: Jeecg
 * @Description: logo component
-->
<template>
  <div class="anticon" :class="getAppLogoClass" @click="goHome">
    <!-- 深色底用白色图形、浅色底用深色图形：同一份齿轮 logo 的两种墨色，背景均透明 -->
    <img :src="logoSrc" alt="永鑫WMS" />
    <div class="ml-2 truncate md:opacity-100" :class="getTitleClass" v-show="showTitle">
      {{ shortTitle }}
    </div>
  </div>
</template>
<script lang="ts" setup>
  import { computed, unref } from 'vue';
  import logoInk from '/@/assets/images/logo.png';
  import logoWhite from '/@/assets/images/logo-white.png';
  import { useGlobSetting } from '/@/hooks/setting';
  import { useGo } from '/@/hooks/web/usePage';
  import { useMenuSetting } from '/@/hooks/setting/useMenuSetting';
  import { useDesign } from '/@/hooks/web/useDesign';
  import { PageEnum } from '/@/enums/pageEnum';
  import { useUserStore } from '/@/store/modules/user';

  const props = defineProps({
    /**
     * The theme of the current parent component
     */
    theme: { type: String, validator: (v: string) => ['light', 'dark'].includes(v) },
    /**
     * Whether to show title
     */
    showTitle: { type: Boolean, default: true },
    /**
     * The title is also displayed when the menu is collapsed
     */
    alwaysShowTitle: { type: Boolean },
  });

  const { prefixCls } = useDesign('app-logo');
  const { getCollapsedShowTitle } = useMenuSetting();
  const userStore = useUserStore();
  const { title, shortTitle } = useGlobSetting();
  
  const go = useGo();

  const getAppLogoClass = computed(() => [prefixCls, props.theme, { 'collapsed-show-title': unref(getCollapsedShowTitle) }]);

  /** 深色底（左侧菜单/混合菜单）用白色 logo，浅色底（登录卡）用深色 logo */
  const logoSrc = computed(() => (props.theme === 'dark' ? logoWhite : logoInk));

  const getTitleClass = computed(() => [
    `${prefixCls}__title`,
    {
      'xs:opacity-0': !props.alwaysShowTitle,
    },
  ]);

  function goHome() {
    go(userStore.getUserInfo.homePath || PageEnum.BASE_HOME);
  }
</script>
<style lang="less" scoped>
  @prefix-cls: ~'@{namespace}-app-logo';

  .@{prefix-cls} {
    display: flex;
    align-items: center;
    padding-left: 7px;
    cursor: pointer;
    transition: all 0.2s ease;
    //左侧菜单模式和左侧菜单混合模式加渐变背景色
    &.jeecg-layout-mix-sider-logo,&.jeecg-layout-menu-logo{
      background:@sider-logo-bg-color;
    }
    // &.light {
    //   border-bottom: 1px solid @border-color-base;
    // }

    &.collapsed-show-title {
      padding-left: 20px;
    }

    &.light &__title {
      color: @primary-color;
    }

    &.dark &__title {
      color: @white;
    }

    &__title {
      font-size: 18px;
      font-weight: 600;
      transition: all 0.5s;
      line-height: normal;
    }
  }
</style>

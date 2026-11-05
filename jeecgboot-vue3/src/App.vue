<template>
  <ConfigProvider :theme="appTheme" :locale="getAntdLocale">
    <AppProvider>
      <RouterView />
    </AppProvider>
  </ConfigProvider>
</template>

<script lang="ts" setup>
  import { watch, ref } from 'vue';
  import { theme } from 'ant-design-vue';
  import { ConfigProvider } from 'ant-design-vue';
  import { AppProvider } from '/@/components/Application';
  import { useTitle } from '/@/hooks/web/useTitle';
  import { useLocale } from '/@/locales/useLocale';
  import { useAppStore } from '/@/store/modules/app';
  import { useRootSetting } from '/@/hooks/setting/useRootSetting';
  import { ThemeEnum } from '/@/enums/appEnum';
  import { changeTheme } from '/@/logics/theme/index';
  import { buildFontTokens, getTextScale, initFontScale, lineScaleRef, textScaleRef } from '/@/hooks/web/useFontScale';

  const appStore = useAppStore();
  // 解决日期时间国际化问题
  import 'dayjs/locale/zh-cn';
  // support Multi-language
  const { getAntdLocale } = useLocale();

  useTitle();
  // 恢复上次的字体缩放设置（zoom 落到 body；text 经由下方 watch 联动 token）
  initFontScale();
  /**
   * 2024-04-07
   * liaozhiyang
   * 暗黑模式下默认文字白色，白天模式默认文字 #333
   * */
  const modeAction = (data) => {
    if (data.token) {
      if (getDarkMode.value === ThemeEnum.DARK) {
        Object.assign(data.token, { colorTextBase: 'fff' });
      } else {
        Object.assign(data.token, { colorTextBase: '#333' });
      }

      // 定义主题色 css 变量
      if (data.token.colorPrimary) {
        document.documentElement.style.setProperty('--j-global-primary-color', data.token.colorPrimary);
      }
    }
  };
  // 代码逻辑说明: 【QQYUN-6366】升级到antd4.x
  const appTheme: any = ref({});
  const { getDarkMode } = useRootSetting();
  watch(
    () => getDarkMode.value,
    (newValue) => {
      delete appTheme.value.algorithm;
      if (newValue === ThemeEnum.DARK) {
        appTheme.value.algorithm = theme.darkAlgorithm;
      }
      // 代码逻辑说明: 【QQYUN-8570】生产环境暗黑模式下主题色不生效
      if (import.meta.env.PROD) {
        changeTheme(appStore.getProjectConfig.themeColor);
      }
      modeAction(appTheme.value);
      appTheme.value = {
        ...appTheme.value,
      };
    },
    { immediate: true }
  );
  watch(
    appStore.getProjectConfig,
    (newValue) => {
      const primary = newValue.themeColor;
      const result = {
        ...appTheme.value,
        ...{
          token: {
            colorPrimary: primary,
            wireframe: true,
            // 字号与控件高度必须一起缩放：antd 的 controlHeight 是独立种子 token，
            // 只放大字号会让文字压出按钮/输入框（见 useFontScale 注释）
            ...buildFontTokens(getTextScale()),
            colorTextBase: '#333',
            colorSuccess: '#55D187',
            colorInfo: primary,
            borderRadius: 4,
            sizeStep: 4,
            sizeUnit: 4,
            colorWarning: '#EFBD47',
            colorError: '#ED6F6F',
            fontFamily:
              '-apple-system,BlinkMacSystemFont,Segoe UI,PingFang SC,Hiragino Sans GB,Microsoft YaHei,Helvetica Neue,Helvetica,Arial,sans-serif,Apple Color Emoji,Segoe UI Emoji,Segoe UI Symbol',
          },
        },
      };
      appTheme.value = result;
      modeAction(result);
    },
    { immediate: true }
  );
  // 文字缩放比例变化时同步 antd token 基准字号（界面缩放走 body zoom，与此处无关）
  // 行间距变化同样走这条链路：buildFontTokens 会按当前行距档位算 lineHeight
  watch(lineScaleRef, () => {
    appTheme.value = {
      ...appTheme.value,
      token: {
        ...appTheme.value.token,
        ...buildFontTokens(getTextScale()),
      },
    };
  });
  watch(textScaleRef, (pct) => {
    appTheme.value = {
      ...appTheme.value,
      token: {
        ...appTheme.value.token,
        ...buildFontTokens(pct),
      },
    };
  });
  setTimeout(() => {
    appStore.getProjectConfig?.themeColor && changeTheme(appStore.getProjectConfig.themeColor);
  }, 300);

</script>
<style lang="less">
  // 代码逻辑说明: 【QQYUN-5839】windi会影响到html2canvas绘制的图片样式
  img {
    display: inline-block;
  }
</style>

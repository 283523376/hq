import { addCollection } from '@iconify/iconify';

/**
 * 离线注册 ant-design 图标集。
 *
 * 为什么需要它：菜单图标名存在数据库里（`sys_permission.icon`），构建期扫描不到，
 * 所以 purge-icons 打不进包；dev 走 `VITE_GLOB_ICONIFY_USE_TYPE=online` 时，
 * 每个图标都要实时请求 `api.iconify.design`。现场是工厂内网，CDN 不可达，
 * 结果就是「有的菜单有图标、有的没有」甚至整片空白（2026-10-27 用无网浏览器复现：
 * 全部菜单图标都是空的占位 span）。
 *
 * 这里把 ant-design 全集一次性注册进 Iconify 单例（`@purge-icons/generated` 与
 * `@iconify/iconify` 是同一个模块实例），dev / 生产、联网 / 断网都能渲染，
 * 也不再受 CDN 抖动影响。
 */
export async function setupOfflineMenuIcons(): Promise<void> {
  try {
    // 动态 import：不拖慢首屏同步链路，数据到货后再注册
    const mod: any = await import('@iconify/json/json/ant-design.json');
    const collection = mod?.default ?? mod;
    if (collection?.icons) {
      addCollection(collection);
    }
  } catch (e) {
    // 注册失败只影响图标显示，不能把启动带崩
    console.warn('[icon] 离线图标注册失败，菜单图标可能缺失：', e);
  }
}

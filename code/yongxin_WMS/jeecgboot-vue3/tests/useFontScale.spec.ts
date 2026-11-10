/**
 * useFontScale 核心逻辑单元测试。
 *
 * <p>只测纯逻辑（百分比换算、步进取整、localStorage 读写、zoom 应用）；
 * Vue 组件接线（App.vue token 联动、系统配置页面板）本项目暂无 vue-jest 转换，
 * 由 eslint + 真实浏览器验证。
 *
 * <p>DOM 应用函数全部依赖注入目标元素（默认 document.body），
 * 测试里用假元素断言，避免依赖 jsdom 对非标准属性 zoom 的支持程度。
 */
describe('useFontScale', () => {
  let mod: any;

  beforeEach(async () => {
    jest.resetModules();
    window.localStorage.clear();
    mod = await import('/@/hooks/web/useFontScale');
  });

  function fakeEl(): { style: Record<string, string> } {
    return { style: {} };
  }

  describe('calcTextFontSize', () => {
    test('100% 对应基准 14px', () => {
      expect(mod.calcTextFontSize(100)).toBe(14);
    });

    test('150% 对应 21px', () => {
      expect(mod.calcTextFontSize(150)).toBe(21);
    });

    test('80% 四舍五入对应 11px', () => {
      // 14 * 0.8 = 11.2 → 11
      expect(mod.calcTextFontSize(80)).toBe(11);
    });
  });

  describe('clampScale', () => {
    test('范围内数值按 5% 步进取整', () => {
      expect(mod.clampScale(122, 80, 150)).toBe(120);
      expect(mod.clampScale(123, 80, 150)).toBe(125);
    });

    test('超出范围收敛到边界', () => {
      expect(mod.clampScale(163, 80, 150)).toBe(150);
      expect(mod.clampScale(10, 50, 200)).toBe(50);
    });
  });

  describe('applyZoomScale', () => {
    test('把百分比写入目标元素 style.zoom', () => {
      const el = fakeEl();
      mod.applyZoomScale(125, el);
      expect(el.style.zoom).toBe('1.25');
    });

    test('100% 时清空 zoom 而不是写 1', () => {
      const el = fakeEl();
      el.style.zoom = '1.25';
      mod.applyZoomScale(100, el);
      expect(el.style.zoom).toBe('');
    });
  });

  describe('setScale / readStoredScale 持久化', () => {
    test('setScale 写入 localStorage，readStoredScale 能读回', () => {
      mod.setScale('text', 120);
      mod.setScale('zoom', 125);
      expect(mod.readStoredScale()).toEqual({ text: 120, zoom: 125 });
    });

    test('setScale 同步更新响应式状态（供 App.vue 联动 antd token）', () => {
      mod.setScale('text', 120);
      expect(mod.getTextScale()).toBe(120);
      expect(mod.textScaleRef.value).toBe(120);
    });

    test('无存储时返回默认 100/100', () => {
      expect(mod.readStoredScale()).toEqual({ text: 100, zoom: 100 });
    });

    test('存储内容损坏时返回默认值', () => {
      window.localStorage.setItem('wms_font_scale', '{bad json');
      expect(mod.readStoredScale()).toEqual({ text: 100, zoom: 100 });
    });

    test('存储数值越界时收敛回合法范围内', () => {
      window.localStorage.setItem('wms_font_scale', JSON.stringify({ text: 999, zoom: -20 }));
      expect(mod.readStoredScale()).toEqual({ text: 150, zoom: 50 });
    });
  });

  describe('initFontScale', () => {
    test('启动时按存储值恢复 zoom 到目标元素并同步状态', () => {
      window.localStorage.setItem('wms_font_scale', JSON.stringify({ text: 110, zoom: 125 }));
      const el = fakeEl();
      mod.initFontScale(el);
      expect(el.style.zoom).toBe('1.25');
      expect(mod.getTextScale()).toBe(110);
      expect(mod.getZoomScale()).toBe(125);
    });

    test('无存储时保持默认 100%，不写任何 zoom', () => {
      const el = fakeEl();
      mod.initFontScale(el);
      // 100% 时实现写入空串（在真实 DOM 上等价于移除 zoom 声明）
      expect(el.style.zoom).toBe('');
      expect(mod.getTextScale()).toBe(100);
      expect(mod.getZoomScale()).toBe(100);
    });
  });
});

/** 打印数据载荷（后端 WmsPrintDataVO 逐字段对应） */
export interface WmsPrintDataVO {
  billType: string;
  title: string;
  erpBillNo: string;
  headers: Record<string, string>;
  columns: string[];
  pages: string[][][];
  rowsPerPage: number;
  totalPages: number;
  totalsOnLastPage: Record<string, string>;
  footers: string[];
  extras: Record<string, any>;
  snapshotTime?: string | null;
}

/** 打印页签：客户 12 类单据；样式未定的 5 类置灰（2026-11-09 只定稿 7 类） */
export interface PrintTab {
  type: string;
  label: string;
  enabled: boolean;
  /** 单号来源：local = 本地单据下拉；erp = 金蝶单号手输 */
  source: 'local' | 'erp';
}

export const PRINT_TABS: PrintTab[] = [
  { type: 'LOSS', label: '盘亏单', enabled: true, source: 'local' },
  { type: 'PROFIT', label: '盘盈单', enabled: true, source: 'local' },
  { type: 'MISC_DELIVERY', label: '其他出库单', enabled: true, source: 'erp' },
  { type: 'MISC_INSTOCK', label: '其他入库单', enabled: true, source: 'erp' },
  { type: 'PROD_FEED', label: '生产补料单', enabled: false, source: 'erp' },
  { type: 'PROD_PICK', label: '生产领料单', enabled: false, source: 'erp' },
  { type: 'PROD_RETURN', label: '生产退料单', enabled: false, source: 'erp' },
  { type: 'SUB_FEED', label: '委外补料单', enabled: false, source: 'erp' },
  { type: 'SUB_PICK', label: '委外领料单', enabled: true, source: 'erp' },
  { type: 'SUB_RETURN', label: '委外退料单', enabled: false, source: 'erp' },
  { type: 'SALES_OUTSTOCK', label: '销售出库单', enabled: true, source: 'local' },
  { type: 'SALES_RETURN', label: '销售退货单', enabled: true, source: 'erp' },
];

/** 单号输入提示（本地类型提示选单，金蝶类型提示输号） */
export const BILL_NO_HINTS: Record<string, string> = {
  LOSS: '选择盘点单（PD…），将打印其回写的盘亏单 QTCK…',
  PROFIT: '选择盘点单（PD…），将打印其回写的盘盈单 QTRK…',
  SALES_OUTSTOCK: '本地单号 XS… 或金蝶单号 XSCK…',
  MISC_DELIVERY: '金蝶其他出库单号，如 QTCK10126110327',
  MISC_INSTOCK: '金蝶其他入库单号，如 QTRK10126110010',
  SUB_PICK: '金蝶委外领料单号，如 WMLL00010491',
  SALES_RETURN: '金蝶销售退货单号，如 XSTHD10126110003',
};

/** 工作台搜索框提示（模糊匹配当前类型单据号） */
export const SEARCH_PLACEHOLDERS: Record<string, string> = {
  LOSS: '盘点单号模糊搜索，如 PD2026',
  PROFIT: '盘点单号模糊搜索，如 PD2026',
  SALES_OUTSTOCK: '销售出库单号模糊搜索，如 XS21 / XSCK',
  MISC_DELIVERY: '金蝶其他出库单号模糊搜索，如 QTCK',
  MISC_INSTOCK: '金蝶其他入库单号模糊搜索，如 QTRK',
  SUB_PICK: '委外领料单号模糊搜索，如 WMLL',
  SALES_RETURN: '销售退货单号模糊搜索，如 XSTHD',
};

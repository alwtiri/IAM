/** UI strings. Arabic is a first-class, RTL locale (Phase 0 G-04). Keys are shared by all locales. */
export type Locale = 'en' | 'ar';

export const messages = {
  en: {
    appTitle: 'Enterprise IAM / PAM',
    appSubtitle: 'Identity, Access, Governance & Privileged Access Control Plane',
    phaseNotice: 'Phase 1 foundation — business functions are delivered from Phase 2.',
    switchLanguage: 'العربية',
    healthLabel: 'Platform health',
    healthUnknown: 'Unknown (requires sign-in; available from Phase 2)',
  },
  ar: {
    appTitle: 'منصة إدارة الهوية والوصول المميز',
    appSubtitle: 'منصة التحكم المركزية للهوية والوصول والحوكمة والوصول المميز',
    phaseNotice: 'أساس المرحلة الأولى — تُسلَّم الوظائف التشغيلية ابتداءً من المرحلة الثانية.',
    switchLanguage: 'English',
    healthLabel: 'حالة المنصة',
    healthUnknown: 'غير معروفة (تتطلب تسجيل الدخول؛ متاحة من المرحلة الثانية)',
  },
} as const satisfies Record<Locale, Record<string, string>>;

export type MessageKey = keyof (typeof messages)['en'];

export function directionOf(locale: Locale): 'ltr' | 'rtl' {
  return locale === 'ar' ? 'rtl' : 'ltr';
}

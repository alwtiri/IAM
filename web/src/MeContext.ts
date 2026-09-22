import { createContext, useContext } from 'react';
import type { EffectiveAccess } from './api/types';

/** The signed-in identity and its effective grants (from GET /me). */
export const MeContext = createContext<EffectiveAccess | undefined>(undefined);

export function useMe(): EffectiveAccess | undefined {
  return useContext(MeContext);
}

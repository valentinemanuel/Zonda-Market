import type { ComponentPropsWithoutRef } from "react";

export const profileControl = "w-full rounded-lg border border-input bg-background px-3 py-2.5";
export const profileLink = "inline-flex min-h-11 items-center text-primary underline underline-offset-4";

export function ProfileInput({ label, id, ...input }: ComponentPropsWithoutRef<"input"> & { label: string; id: string }) {
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium">{label}</label>
      <input id={id} className={profileControl} {...input} />
    </div>
  );
}

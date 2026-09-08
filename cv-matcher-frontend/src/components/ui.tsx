import { type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode } from "react";
import { cn } from "../lib/utils";

export function Button({ className, variant = "primary", ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger" }) {
  const variants = {
    primary: "bg-brand text-white hover:bg-brand-dark",
    secondary: "bg-white text-ink ring-1 ring-slate-300 hover:bg-slate-50",
    danger: "bg-rose-700 text-white hover:bg-rose-800",
  };
  return <button className={cn("inline-flex min-h-11 items-center justify-center rounded-lg px-4 font-semibold transition disabled:cursor-not-allowed disabled:opacity-50", variants[variant], className)} {...props} />;
}

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn("min-h-11 w-full rounded-lg border bg-white px-3 shadow-xs placeholder:text-slate-400", className)} {...props} />;
}

export function Card({ children, className }: { children: ReactNode; className?: string }) {
  return <section className={cn("rounded-2xl border bg-white p-6 shadow-sm", className)}>{children}</section>;
}

export function Notice({ children, tone = "error" }: { children: ReactNode; tone?: "error" | "info" | "success" }) {
  const tones = { error: "border-rose-200 bg-rose-50 text-rose-900", info: "border-sky-200 bg-sky-50 text-sky-900", success: "border-emerald-200 bg-emerald-50 text-emerald-900" };
  return <div role={tone === "error" ? "alert" : "status"} className={cn("rounded-lg border p-3 text-sm", tones[tone])}>{children}</div>;
}

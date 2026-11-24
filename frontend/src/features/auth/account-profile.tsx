import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Store } from "lucide-react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { activateSeller, ownSellerKey, type SellerType, updateProfile } from "./profile-api";
import { ProfileInput, profileControl, profileLink } from "./profile-fields";
import { type Account, getSessionRevision, renewSession, currentAccountKey } from "./session-api";
import { AccountSection } from "./account-layout";

type AccountProps = { account: Account; revision: number };

export function AccountProfile({ account, revision }: AccountProps) {
  const queryClient = useQueryClient();
  const save = useMutation({
    mutationFn: updateProfile,
    onSuccess: (updated) => {
      if (revision === getSessionRevision()) {
        queryClient.setQueryData(currentAccountKey(revision), updated);
      }
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    save.mutate(String(new FormData(event.currentTarget).get("displayName")));
  }

  return (
    <form className="space-y-4" onSubmit={submit} aria-busy={save.isPending}>
      <ProfileInput id="profile-display-name" name="displayName" label="Nombre de perfil"
        defaultValue={account.displayName ?? ""} autoComplete="name" maxLength={80} required />
      {save.isError && <p role="alert" className="text-sm text-destructive">
        No pudimos guardar el nombre. Revisá el dato y volvé a intentar.
      </p>}
      {save.isSuccess && <p role="status" className="text-sm">Nombre guardado.</p>}
      <Button type="submit" disabled={save.isPending} className="min-h-11">
        {save.isPending ? "Guardando…" : "Guardar nombre"}
      </Button>
    </form>
  );
}

export function AccountSeller({ account, revision }: AccountProps) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const activation = useMutation({
    mutationFn: activateSeller,
    onSuccess: async (seller) => {
      if (revision !== getSessionRevision()) return;
      // The persisted role is authoritative even if the browser has to retry renewal later.
      await renewSession().catch(() => undefined);
      if (revision !== getSessionRevision()) return;
      queryClient.setQueryData(ownSellerKey(revision), seller);
      void queryClient.invalidateQueries({ queryKey: currentAccountKey(revision) });
      navigate("/vendedor");
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === "SELLER_ALREADY_ACTIVE") {
        void queryClient.invalidateQueries({ queryKey: currentAccountKey(revision) });
      }
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    activation.mutate({
      type: data.get("type") as SellerType,
      displayName: String(data.get("sellerName")),
      slug: String(data.get("slug")),
      bio: String(data.get("bio")),
    });
  }

  const code = activation.error instanceof ApiError ? activation.error.code : undefined;
  const message = code === "SELLER_SLUG_IN_USE" ? "Ese identificador ya está en uso. Elegí otro."
    : code === "EMAIL_NOT_VERIFIED" ? "Verificá tu correo antes de activar el perfil."
    : code === "SELLER_ALREADY_ACTIVE" ? "Esta cuenta ya tiene un perfil de vendedor."
    : code === "INVALID_REQUEST" ? "Revisá el nombre y el identificador. Usá letras, números o guiones."
    : "No pudimos activar el perfil. Volvé a intentar.";

  return (
    <AccountSection id="seller-title" title="Vender en Zonda" icon={Store} className="lg:col-span-2">
      {account.roles.includes("SELLER") ? (
        <Link className={profileLink} to="/vendedor">Panel vendedor</Link>
      ) : account.roles.includes("USER") && !account.emailVerified ? (
        <p className="mt-2 text-sm text-muted-foreground">Verificá tu correo para activar tu perfil de vendedor.</p>
      ) : account.roles.includes("USER") && (
        <form className="grid gap-4 sm:grid-cols-2" onSubmit={submit} aria-busy={activation.isPending}>
          <div>
            <label htmlFor="seller-type" className="mb-1.5 block text-sm font-medium">Tipo de vendedor</label>
            <select id="seller-type" name="type" className={profileControl}>
              <option value="PERSONAL">Personal</option>
              <option value="COMERCIO">Comercio</option>
            </select>
          </div>
          <ProfileInput id="seller-name" name="sellerName" label="Nombre público" maxLength={80} required />
          <div>
            <ProfileInput id="seller-slug" name="slug" label="Identificador público" minLength={3} maxLength={50}
              autoComplete="off" required aria-describedby="seller-slug-hint" />
            <p id="seller-slug-hint" className="mt-2 text-sm text-muted-foreground">Se usa en el enlace de tu ficha. Ejemplo: taller-del-oeste.</p>
          </div>
          <div>
            <label htmlFor="seller-bio" className="mb-1.5 block text-sm font-medium">Descripción (opcional)</label>
            <textarea id="seller-bio" name="bio" className={profileControl} rows={3} maxLength={500} />
          </div>
          {activation.isError && <p role="alert" className="text-sm text-destructive sm:col-span-2">{message}</p>}
          <Button type="submit" disabled={activation.isPending} className="min-h-11 justify-self-start sm:col-span-2">
            {activation.isPending ? "Activando…" : "Activar perfil de vendedor"}
          </Button>
        </form>
      )}
    </AccountSection>
  );
}

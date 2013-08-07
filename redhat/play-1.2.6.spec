%define play_install_path	/usr/share/play/%{version}
%define play_etc_path		/etc/play/%{version}
%define play_doc_path		/usr/share/doc/play-%{version}-%{version}


Name:		play-1.2.6
Version:	1.2.6
Release:	1%{?dist}
Summary:	Play! Framework

Group:		Development/Languages
License:	ASL
URL:		http://www.playframework.org
Source0:	play-1.2.6-1.2.6.tar.gz
BuildRoot:	%(mktemp -ud %{_tmppath}/%{name}-%{version}-%{release}-XXXXXX)
BuildArch:	noarch

BuildRequires:	ant, java-1.7.0, python >= 2.5
Requires:	java-1.7.0, python >= 2.5
Provides:	play-1.2

%description
This is the Play! Framework packaged for RH

%prep
%setup -q


%build
cd framework; ant; cd ..
./play help > /dev/null

%install
rm -rf $RPM_BUILD_ROOT
install -d $RPM_BUILD_ROOT/%{play_install_path} $RPM_BUILD_ROOT/%{play_install_path}/modules \
           $RPM_BUILD_ROOT/%{play_etc_path} $RPM_BUILD_ROOT/%{play_doc_path}
rsync -r --exclude framework/classes framework play resources support $RPM_BUILD_ROOT/%{play_install_path}/
for m in console crud docviewer grizzly secure testrunner; \
 do rsync -r --exclude tmp modules/$m $RPM_BUILD_ROOT/%{play_install_path}/modules/; \
done
cp -r documentation $RPM_BUILD_ROOT/%{play_doc_path}
ln -s %{play_doc_path}/documentation $RPM_BUILD_ROOT/%{play_install_path}
echo prod > $RPM_BUILD_ROOT/%{play_etc_path}/id
ln -s %{play_etc_path} $RPM_BUILD_ROOT/%{play_install_path}/etc
ln -s %{play_etc_path}/id $RPM_BUILD_ROOT/%{play_install_path}/id


%clean
rm -rf $RPM_BUILD_ROOT
cd framework; ant clean; cd ..


%files
%defattr(-,root,root,-)
%{play_doc_path}
%{play_install_path}
%config %{play_etc_path}

%post
update-alternatives --install /usr/bin/play-1.2 play-1.2 %{play_install_path}/play 1261000001
%preun
update-alternatives --remove play-1.2 %{play_install_path}/play

%changelog


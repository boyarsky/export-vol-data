package com.jeanneboyarsky.first;

import static com.jeanneboyarsky.first.util.AlertWorkarounds.*;
import static com.jeanneboyarsky.first.util.Constants.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import org.apache.commons.csv.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.support.ui.*;

import com.jeanneboyarsky.first.model.*;
import com.jeanneboyarsky.first.util.*;

/**
 * Screen scrapes info not in the export (FIRST experience field). Assumes no
 * two volunteers have the same first/last name combination. Skips any
 * volunteers already processed and in the CSV file to support resuming. Assumes
 * at least 1 unassigned applicant.
 * 
 * Note: get alerts from VMS system. Just ignore them in the browser, it seems
 * to proceed anyway
 * 
 * @author Jeanne
 *
 */

// printlns ok because a command line program
@SuppressWarnings("squid:S106")
public class ExportVolunteerDetail implements AutoCloseable {

	private static final String CHROME_DRIVER_DIRECTORY = "chromedriver-2-37";
	private static final boolean HEADLESS = false;

	private WebDriver driver;
	private CSVPrinter printer;
	private PrintWriter statusWriter;
	private EventAndRoleTracker tracker;
	private NameUrlPair currentEvent;
	private VolunteerInfoFile volunteerFileCache;

	// -------------------------------------------------------

	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.out.println("Pass two parameters: email and password");
			System.out.println("ex: com.jeanneboyarsky.first.ExportVolunteerDetail email password");
			System.exit(1);
		}

		Path csvPath = createAndGetFile(CSV_DATA);
		Path statusPath = createAndGetFile(STATUS_TRACKER_FILE);

		VolunteerInfoFile volunteerFileCache = new VolunteerInfoFile();
		volunteerFileCache.loadFile(csvPath);
		long startTime = System.currentTimeMillis();

		try (BufferedWriter csvWriter = Files.newBufferedWriter(csvPath, StandardOpenOption.APPEND);
				BufferedWriter statusBufWriter = Files.newBufferedWriter(statusPath, StandardOpenOption.APPEND);
				PrintWriter statusWriter = new PrintWriter(statusBufWriter);
				CSVPrinter printer = new CSVPrinter(csvWriter, CSVFormat.DEFAULT);
				ExportVolunteerDetail detail = new ExportVolunteerDetail(printer, statusWriter, volunteerFileCache)) {

			detail.login(args[0], args[1]);
			detail.execute(detail);
		} finally {
			long endTime = System.currentTimeMillis();
			long duration = endTime - startTime;
			System.out.println("Done. This took " + (duration / 1000) + " seconds to run");
		}
	}

	private static Path createAndGetFile(String name) throws IOException {
		Path path = Paths.get(name);
		if (!path.toFile().exists()) {
			Files.createFile(path);
		}
		return path;
	}

	// -------------------------------------------------------

	private ExportVolunteerDetail(CSVPrinter printer, PrintWriter statusWriter, VolunteerInfoFile volunteerFileCache)
			throws IOException {
		this.printer = printer;
		this.statusWriter = statusWriter;
		this.volunteerFileCache = volunteerFileCache;

		// the FIRST site doesn't work with the htmlunit or phantomjs drivers
		// it is also slow/flakey in firefox
		Path chrome = Paths.get(CHROME_DRIVER_DIRECTORY + "/chromedriver");
		chrome.toFile().setExecutable(true);
		System.setProperty("webdriver.chrome.driver", chrome.toAbsolutePath().toString());

		ChromeOptions chromeOptions = new ChromeOptions();
		if (HEADLESS) {
			chromeOptions.addArguments("--headless");
		}
		
		driver = new ChromeDriver(chromeOptions);

		// https://github.com/seleniumhq/selenium-google-code-issue-archive/issues/27
		((JavascriptExecutor) driver).executeScript("window.alert = function(msg) { }");
		((JavascriptExecutor) driver).executeScript("window.confirm = function(msg) { }");

		tracker = new EventAndRoleTracker(driver);
	}

	private void login(String userName, String password) {
		driver.get(HOME_PAGE);
		driver.findElement(By.id("EmailAddressTextBox")).sendKeys(userName);
		driver.findElement(By.id("PasswordTextBox")).sendKeys(password);
		driver.findElement(By.id("LoginButton")).click();
	}

	private void execute(ExportVolunteerDetail detail) {
		tracker.getRemainingEvents().forEach(e -> {
			currentEvent = e;
			detail.setVolunteerInfoForAllRoles();
			detail.setVolunteerInfoForUnassigned();
			statusWriter.println("Completed logging for event: " + currentEvent.getName());
		});

	}

	/*
	 * Can get unassigned applicants from any role so just pick one at random
	 */
	private void setVolunteerInfoForUnassigned() {
		NameUrlPair roleUrl = tracker.getAnyRoleForEventByUrl(currentEvent);
		System.out.println(roleUrl.getName() + " for unassigned volunteers");
		driver.get(roleUrl.getUrl());

		// TODO didn't work consistently, but only a few applicants so not worth
		// troubleshooting
		/*
		 * try { getUnassigned(); } catch (NoSuchElementException e) {
		 * System.out.println("Retrying unassigned due to: " + e.getMessage());
		 * getUnassigned(); } }
		 * 
		 * private void getUnassigned() {
		 * driver.findElement(By.id("UnassignedTab")).click();
		 * setVolunteerInfoForSingleRole("Unassigned", "UnassignedTable", false);
		 */
	}

	private void setVolunteerInfoForAllRoles() {
		List<NameUrlPair> roleNameToUrl = tracker.getRemainingRolesForEventByUrl(currentEvent);
		roleNameToUrl.forEach(p -> {

			System.out.println("--- Role: " + p.getName() + "---");

			driver.get(p.getUrl());
			setVolunteerInfoForSingleRole(p.getName(), "ScheduleTable", true);

			statusWriter.println("Completed logging for event/role: " + currentEvent.getName() + "/" + p.getName());
		});
	}

	private void setVolunteerInfoForSingleRole(String roleName, String tableId, boolean includeRoleAssignment) {
		List<NameUrlPair> volunteerUrls = null;
		try {
			volunteerUrls = getNewVolunteerUrls(tableId);
		} catch (TimeoutException e) {
			// a better way of handling this would be to add a guard clause
			System.out.println("Skipping because no volunteers in this role");
			return;
		}
		for (NameUrlPair volunteerPair : volunteerUrls) {
			// skip this record if got in previous run
			String volunteerName = volunteerPair.getName();
			if (volunteerFileCache.isLogged(currentEvent.getName(), roleName, volunteerName)) {
				System.out.println("Skipping because already logged: " + currentEvent.getName() + ", " + roleName + ", "
						+ volunteerName);
			} else {
				Optional<VolunteerDetail> optional = volunteerFileCache.getVolunteerInfo(volunteerName);
				VolunteerDetail detail;
				// used cached volunteer info if have already looked it up
				if (optional.isPresent()) {
					System.out.println("Using cached info volunteer details for: " + volunteerName);
					detail = optional.get();
				} else {
					detail = getVolunteerDetailWithRetry(includeRoleAssignment, volunteerPair);
				}

				printOneRecord(roleName, detail);
			}
		}
	}

	// this is where the program most fails so added a retry
	private VolunteerDetail getVolunteerDetailWithRetry(boolean includeRoleAssignment, NameUrlPair volunteerPair) {
		VolunteerDetail result;
		try {
			result = getVolunteerDetail(includeRoleAssignment, volunteerPair);
		} catch (org.openqa.selenium.NoSuchElementException e) {
			System.out.println("NoSuchElementException " + e + " - retrying");
			result = getVolunteerDetail(includeRoleAssignment, volunteerPair);
		}
		return result;
	}

	private VolunteerDetail getVolunteerDetail(boolean includeRoleAssignment, NameUrlPair volunteerPair) {

		driver.get(volunteerPair.getUrl());

		String commentText = "";

		WebElement secondarySection = driver.findElement(By.className("secondarySection"));
		String name = getText(secondarySection.findElement(By.tagName("h2")));
		List<WebElement> personalComments = driver.findElements(By.className("personalLabel"));

		System.out.println("Processing " + name);

		List<String> personalDetails = personalComments.stream().map(WebElement::getText).collect(Collectors.toList());

		if (includeRoleAssignment) {
			driver.findElement(By.id("MainContent_RolePreferencesLinkButton")).click();
			WebElement comments = getElementByIdAfterTimeout("MainContent_VolunteerComments");
			commentText = getText(comments);
		}

		return new VolunteerDetail(name, commentText, personalDetails);
	}

	/*
	 * Convert checked exception to runtime exception so can use with lambdas
	 */
	private void printOneRecord(String roleName, VolunteerDetail detail) {
		try {
			Object[] fields = detail.getAsArray(currentEvent.getName(), roleName);
			printer.printRecord(fields);
			volunteerFileCache.addNewLine(currentEvent.getName(), roleName, detail);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private List<NameUrlPair> getNewVolunteerUrls(String tableId) {
		WebElement table = getElementByIdAfterTimeout(tableId);
		List<WebElement> volunteers = table.findElements(By.cssSelector("a[href*=People]"));

		return volunteers.stream().filter(new DistinctByKey<>(WebElement::getText)::filter).map(NameUrlPair::new)
				.collect(Collectors.toList());
	}

	private WebElement getElementByIdAfterTimeout(String id) {
		WebDriverWait wait = new WebDriverWait(driver, 15);
		wait.until(ExpectedConditions.presenceOfElementLocated(By.id(id)));

		return driver.findElement(By.id(id));
	}

	@Override
	public void close() {
		driver.close();
	}

}
